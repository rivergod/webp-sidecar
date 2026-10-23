package net.rivergod.webp.imageio;

import io.grpc.stub.ClientCallStreamObserver;
import io.grpc.stub.ClientResponseObserver;
import java.util.concurrent.TimeUnit;
import javax.imageio.IIOException;

/**
 * 요청 스트림(options → 데이터 조각 → half-close)을 흐름 제어를 지키며 보내고, 응답을 콜백으로 처리하는 동기 어댑터.
 *
 * <p>grpc-java 블로킹 스텁은 클라이언트/양방향 스트리밍을 지원하지 않아 async 스텁을 감싼다(docs/design/grpc-api.md §8).
 * 보내는 쪽은 서버가 받을 준비가 될 때까지 기다리므로 큰 입력도 메모리에 쌓이지 않는다. 응답 콜백은 gRPC 스레드에서
 * 순서대로 불리며, 콜백이 끝난 뒤에 다음 메시지를 요청한다(자동 흐름 제어). 한 호출에 한 스레드가 쓴다.
 *
 * @param <Req> 요청 메시지
 * @param <Resp> 응답 메시지
 */
final class StreamingCall<Req, Resp> implements ClientResponseObserver<Req, Resp> {

    /** 응답 메시지 하나를 처리한다. 예외를 던지면 호출을 취소하고 그 예외를 {@link #await}에서 다시 던진다. */
    @FunctionalInterface
    interface ResponseHandler<Resp> {
        void accept(Resp response) throws Exception;
    }

    private final Object lock = new Object();
    private final ResponseHandler<Resp> handler;
    private final long safetyTimeoutMs;
    private ClientCallStreamObserver<Req> requests;
    private Throwable error;
    private boolean finished;

    /**
     * @param handler 응답 처리기
     * @param deadlineMs 호출 데드라인(gRPC가 강제한다). 대기에는 여기에 여유를 더한 안전 상한을 쓴다. 단위: ms
     */
    StreamingCall(ResponseHandler<Resp> handler, long deadlineMs) {
        this.handler = handler;
        this.safetyTimeoutMs = deadlineMs + 5_000;
    }

    @Override
    public void beforeStart(ClientCallStreamObserver<Req> requestStream) {
        this.requests = requestStream;
        requestStream.setOnReadyHandler(this::signal);
    }

    @Override
    public void onNext(Resp response) {
        try {
            handler.accept(response);
        } catch (Exception e) {
            synchronized (lock) {
                if (error == null) {
                    error = e;
                }
            }
            requests.cancel("client failed to process the response", e);
            signal();
        }
    }

    @Override
    public void onError(Throwable t) {
        synchronized (lock) {
            if (error == null) {
                error = t;
            }
            finished = true;
            lock.notifyAll();
        }
    }

    @Override
    public void onCompleted() {
        synchronized (lock) {
            finished = true;
            lock.notifyAll();
        }
    }

    private void signal() {
        synchronized (lock) {
            lock.notifyAll();
        }
    }

    /**
     * 서버가 받을 준비가 되면 메시지 하나를 보낸다.
     *
     * @return 호출이 이미 끝났으면(오류 등) false. 이때 더 보내지 말고 {@link #await}로 결과를 확인한다
     * @throws InterruptedException 기다리는 중 인터럽트되면
     * @throws IIOException 안전 상한 안에 준비되지 않으면
     */
    boolean send(Req message) throws InterruptedException, IIOException {
        long until = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(safetyTimeoutMs);
        synchronized (lock) {
            while (!finished && error == null && !requests.isReady()) {
                long left = until - System.nanoTime();
                if (left <= 0) {
                    throw new IIOException("timed out waiting for the WebP server to accept data");
                }
                TimeUnit.NANOSECONDS.timedWait(lock, left);
            }
            if (finished || error != null) {
                return false;
            }
        }
        requests.onNext(message);
        return true;
    }

    /** 요청 스트림을 닫는다(half-close). */
    void halfClose() {
        synchronized (lock) {
            if (finished || error != null) {
                return;
            }
        }
        requests.onCompleted();
    }

    /** 호출을 취소한다(abort). */
    void cancel(String reason) {
        requests.cancel(reason, null);
    }

    /**
     * 호출이 끝날 때까지 기다린다.
     *
     * @return 정상 종료면 null, 실패면 원인 예외(응답 처리기 예외 또는 gRPC 오류)
     * @throws InterruptedException 기다리는 중 인터럽트되면
     */
    Throwable await() throws InterruptedException {
        long until = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(safetyTimeoutMs);
        synchronized (lock) {
            while (!finished) {
                long left = until - System.nanoTime();
                if (left <= 0) {
                    requests.cancel("client safety timeout", null);
                    return new IIOException("timed out waiting for the WebP server response");
                }
                TimeUnit.NANOSECONDS.timedWait(lock, left);
            }
            return error;
        }
    }
}
