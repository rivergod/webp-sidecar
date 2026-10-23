package net.rivergod.webp.imageio;

import io.grpc.stub.ClientCallStreamObserver;
import io.grpc.stub.ClientResponseObserver;
import java.util.Iterator;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import javax.imageio.IIOException;
import net.rivergod.webp.imageio.proto.v1.DecodeAnimationRequest;
import net.rivergod.webp.imageio.proto.v1.DecodeAnimationResponse;
import net.rivergod.webp.imageio.proto.v1.ImageFeatures;

/**
 * 열어 둔 DecodeAnimation 스트림(ADR-0014의 순차 읽기). 프레임을 0번부터 하나씩 꺼낸다.
 *
 * <p>수신은 수동 흐름 제어다: 메시지를 하나 소비할 때마다 다음 하나를 요청하므로, 리더가 읽지 않으면 서버 전송도
 * 멈추고 메모리가 쌓이지 않는다. 열어 둔 스트림은 서버 동시 처리 슬롯을 차지하므로 다 쓰면 {@link #close}한다.
 * 한 리더(한 스레드) 전용이다.
 */
final class AnimationStream implements ClientResponseObserver<DecodeAnimationRequest, DecodeAnimationResponse>, AutoCloseable {

    /** 진단용: 지금까지 연 스트림 수(테스트가 순차 읽기에서 스트림을 한 번만 여는지 확인한다). */
    static final AtomicLong OPENED = new AtomicLong();

    private static final Object END = new Object();

    private final BlockingQueue<Object> inbox = new LinkedBlockingQueue<>();
    private final Object readyLock = new Object();
    private final ClientConfig config;
    private ClientCallStreamObserver<DecodeAnimationRequest> requests;
    private ImageFeatures features;
    private int nextFrame;
    private boolean closed;

    AnimationStream(ClientConfig config) {
        this.config = config;
    }

    @Override
    public void beforeStart(ClientCallStreamObserver<DecodeAnimationRequest> requestStream) {
        this.requests = requestStream;
        requestStream.disableAutoRequestWithInitial(1);
        requestStream.setOnReadyHandler(() -> {
            synchronized (readyLock) {
                readyLock.notifyAll();
            }
        });
    }

    @Override
    public void onNext(DecodeAnimationResponse response) {
        inbox.add(response);
    }

    @Override
    public void onError(Throwable t) {
        inbox.add(t);
        synchronized (readyLock) {
            readyLock.notifyAll();
        }
    }

    @Override
    public void onCompleted() {
        inbox.add(END);
    }

    /** 요청(options → 입력 조각)을 서버가 받을 수 있을 때마다 보내고 half-close한 뒤, 첫 메시지(특성)를 받는다. */
    void start(Iterator<DecodeAnimationRequest> messages) throws IIOException {
        OPENED.incrementAndGet();
        try {
            while (messages.hasNext()) {
                synchronized (readyLock) {
                    while (!requests.isReady() && !(inbox.peek() instanceof Throwable)) {
                        readyLock.wait(config.deadlineMs() + 5_000);
                    }
                }
                if (inbox.peek() instanceof Throwable) {
                    break;
                }
                requests.onNext(messages.next());
            }
            requests.onCompleted();
        } catch (InterruptedException e) {
            close();
            Thread.currentThread().interrupt();
            throw new IIOException("Interrupted during WebP DecodeAnimation", e);
        }
        DecodeAnimationResponse first = take();
        if (first.getPayloadCase() != DecodeAnimationResponse.PayloadCase.FEATURES) {
            throw new IIOException("WebP server did not start DecodeAnimation with features");
        }
        features = first.getFeatures();
    }

    ImageFeatures features() {
        return features;
    }

    /** 다음에 꺼낼 프레임 번호. */
    int nextFrame() {
        return nextFrame;
    }

    /**
     * 다음 프레임을 받아 수신기에 넘긴다.
     *
     * @throws IIOException 서버 오류, 데드라인, 스트림이 일찍 끝남, 수신기 검증 실패
     */
    void readFrame(WebPClient.FrameReceiver receiver) throws IIOException {
        DecodeAnimationResponse header = take();
        if (header.getPayloadCase() != DecodeAnimationResponse.PayloadCase.FRAME) {
            throw new IIOException("WebP server sent " + header.getPayloadCase() + " where a frame header was expected");
        }
        try {
            receiver.header(header.getFrame());
            long remaining = header.getFrame().getPixelBytes();
            while (remaining > 0) {
                DecodeAnimationResponse chunk = take();
                if (chunk.getPayloadCase() != DecodeAnimationResponse.PayloadCase.PIXELS) {
                    throw new IIOException("WebP server sent " + chunk.getPayloadCase() + " inside frame pixels");
                }
                receiver.pixels(chunk.getPixels());
                remaining -= chunk.getPixels().size();
            }
            receiver.finish();
        } catch (IIOException e) {
            throw e;
        } catch (Exception e) {
            throw new IIOException("Failed to process WebP frame", e);
        }
        nextFrame++;
    }

    /** 메시지 하나를 꺼내고 다음 하나를 요청한다. 오류·종료·시간 초과는 IIOException. */
    private DecodeAnimationResponse take() throws IIOException {
        Object item;
        try {
            item = inbox.poll(config.deadlineMs() + 5_000, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            close();
            Thread.currentThread().interrupt();
            throw new IIOException("Interrupted during WebP DecodeAnimation", e);
        }
        if (item == null) {
            close();
            throw new IIOException("timed out waiting for the WebP server (DecodeAnimation)");
        }
        if (item instanceof Throwable t) {
            closed = true;
            throw GrpcErrors.toIIOException(t, "DecodeAnimation", config);
        }
        if (item == END) {
            closed = true;
            throw new IIOException("WebP server ended DecodeAnimation early");
        }
        requests.request(1);
        return (DecodeAnimationResponse) item;
    }

    /** 스트림을 취소해 서버 슬롯을 돌려준다. 여러 번 불러도 된다. */
    @Override
    public void close() {
        if (!closed) {
            closed = true;
            requests.cancel("reader closed", null);
        }
    }
}
