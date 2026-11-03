package net.rivergod.webp.sidecar;

import com.google.protobuf.ByteString;
import com.google.protobuf.UnsafeByteOperations;
import io.grpc.ManagedChannel;
import java.util.Iterator;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import javax.imageio.IIOException;
import net.rivergod.webp.sidecar.proto.v1.DecodeAnimationOptions;
import net.rivergod.webp.sidecar.proto.v1.DecodeAnimationRequest;
import net.rivergod.webp.sidecar.proto.v1.DecodeOptions;
import net.rivergod.webp.sidecar.proto.v1.DecodeRequest;
import net.rivergod.webp.sidecar.proto.v1.DecodeResponse;
import net.rivergod.webp.sidecar.proto.v1.EncodeAnimationOptions;
import net.rivergod.webp.sidecar.proto.v1.EncodeAnimationRequest;
import net.rivergod.webp.sidecar.proto.v1.EncodeAnimationResponse;
import net.rivergod.webp.sidecar.proto.v1.EncodeFrame;
import net.rivergod.webp.sidecar.proto.v1.EncodeOptions;
import net.rivergod.webp.sidecar.proto.v1.EncodeRequest;
import net.rivergod.webp.sidecar.proto.v1.EncodeResponse;
import net.rivergod.webp.sidecar.proto.v1.EncodedHeader;
import net.rivergod.webp.sidecar.proto.v1.FrameHeader;
import net.rivergod.webp.sidecar.proto.v1.GetFeaturesOptions;
import net.rivergod.webp.sidecar.proto.v1.GetFeaturesRequest;
import net.rivergod.webp.sidecar.proto.v1.GetFeaturesResponse;
import net.rivergod.webp.sidecar.proto.v1.GetInfoRequest;
import net.rivergod.webp.sidecar.proto.v1.Limits;
import net.rivergod.webp.sidecar.proto.v1.PixelFormat;
import net.rivergod.webp.sidecar.proto.v1.WebPServiceGrpc;

/**
 * webp.v1 RPC를 동기 호출로 감싼다. 스트리밍 규칙은 docs/design/grpc-api.md §2를 따른다. 실패는 모두
 * {@link IIOException}으로 바꾼다({@link GrpcErrors}). 채널은 엔드포인트별로 공유하고, 인스턴스는 가볍다.
 * 한 인스턴스를 여러 스레드에서 써도 되지만 각 호출은 호출한 스레드를 막는다.
 *
 * <p>ADR-0020 혼잡 재시도: 설정의 {@code busyRetries}가 1 이상이면, 서버가 일을 시작하기 전에 "server busy"로 거부한
 * GetFeatures·Decode·DecodeAnimation 시작·Encode를 물러섰다가 다시 부른다. 시도와 물러섬을 합쳐 호출 데드라인 안이다.
 * EncodeAnimation은 프레임을 받는 대로 보내므로 다시 시도하지 않는다.
 */
final class WebPClient {

    /** 클라이언트가 보내는 조각의 최대 크기. 서버 상한이 더 작으면 그 값을 쓴다. 단위: 바이트. (ADR-0003) */
    static final int DEFAULT_CHUNK_BYTES = 1024 * 1024;

    /** 혼잡 재시도의 첫 물러섬. 다음부터 두 배씩 늘린다(ADR-0020). 단위: ms. */
    static final long FIRST_BACKOFF_MS = 100;

    /** 혼잡 재시도의 최대 물러섬(ADR-0020). 단위: ms. */
    static final long MAX_BACKOFF_MS = 1_600;

    private static final ConcurrentMap<String, Limits> LIMITS = new ConcurrentHashMap<>();

    private final ClientConfig config;
    private final ManagedChannel channel;

    WebPClient(ClientConfig config) {
        this.config = config;
        this.channel = WebPChannels.forEndpoint(config.endpoint());
    }

    /**
     * 현재 환경의 설정으로 클라이언트를 만든다.
     *
     * @throws IIOException 설정 값이 잘못됐으면
     */
    static WebPClient fromEnvironment() throws IIOException {
        return of(WebPClientConfig.EMPTY);
    }

    /**
     * 코드로 준 설정을 먼저 쓰고 나머지는 시스템 프로퍼티 → 환경변수 → 기본값으로 채운 클라이언트를 만든다(ADR-0020).
     *
     * @throws IIOException 시스템 프로퍼티·환경변수 값이 잘못됐으면
     */
    static WebPClient of(WebPClientConfig code) throws IIOException {
        try {
            return new WebPClient(ClientConfig.of(code));
        } catch (IllegalArgumentException e) {
            throw new IIOException("Invalid WebP client configuration: " + e.getMessage(), e);
        }
    }

    ClientConfig config() {
        return config;
    }

    /** 호출 하나의 데드라인·픽셀 상한(ADR-0020 {@link WebPReadParam})으로 바꾼 클라이언트. 0인 인자는 지금 값을 둔다. */
    WebPClient forCall(long deadlineMs, long maxPixels) {
        ClientConfig call = config.forCall(deadlineMs, maxPixels);
        return call.equals(config) ? this : new WebPClient(call);
    }

    private WebPServiceGrpc.WebPServiceStub stub(long deadlineMs) {
        return WebPServiceGrpc.newStub(channel).withDeadlineAfter(deadlineMs, TimeUnit.MILLISECONDS);
    }

    /** 한 번의 시도. 인자는 이 시도의 데드라인(혼잡 재시도 뒤에는 남은 시간)이다. 단위: ms. */
    @FunctionalInterface
    private interface Attempt<T> {
        T run(long deadlineMs) throws IIOException;
    }

    /**
     * ADR-0020 혼잡 재시도. "server busy"(서버가 일을 시작하기 전의 거부)면 물러섰다가 다시 부른다. 횟수를 다 썼거나,
     * 남은 시간이 다음 물러섬보다 짧거나, 다른 오류면 마지막 오류를 그대로 낸다.
     */
    private <T> T withBusyRetry(String rpc, Attempt<T> attempt) throws IIOException {
        long end = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(config.deadlineMs());
        long backoff = FIRST_BACKOFF_MS;
        for (int retry = 0; ; retry++) {
            long remaining = TimeUnit.NANOSECONDS.toMillis(end - System.nanoTime());
            try {
                return attempt.run(Math.max(1, remaining));
            } catch (IIOException e) {
                long left = TimeUnit.NANOSECONDS.toMillis(end - System.nanoTime());
                if (retry >= config.busyRetries() || !GrpcErrors.isServerBusy(e) || left <= backoff) {
                    throw e;
                }
                try {
                    Thread.sleep(backoff);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw new IIOException("Interrupted while retrying WebP " + rpc, ie);
                }
                backoff = Math.min(backoff * 2, MAX_BACKOFF_MS);
            }
        }
    }

    /** 서버 상한(GetInfo). 엔드포인트별로 한 번만 묻고 기억한다. */
    Limits limits() throws IIOException {
        Limits cached = LIMITS.get(config.endpoint());
        if (cached != null) {
            return cached;
        }
        try {
            Limits limits = WebPServiceGrpc.newBlockingStub(channel)
                    .withDeadlineAfter(config.deadlineMs(), TimeUnit.MILLISECONDS)
                    .getInfo(GetInfoRequest.getDefaultInstance())
                    .getLimits();
            LIMITS.put(config.endpoint(), limits);
            return limits;
        } catch (RuntimeException e) {
            throw GrpcErrors.toIIOException(e, "GetInfo", config);
        }
    }

    private int chunkBytes() throws IIOException {
        int server = limits().getMaxChunkBytes();
        return server > 0 ? Math.min(server, DEFAULT_CHUNK_BYTES) : DEFAULT_CHUNK_BYTES;
    }

    /**
     * GetFeatures: 픽셀 디코딩 없이 특성(과 선택적으로 메타데이터)을 받는다.
     *
     * @param input 압축된 WebP 파일 전체
     * @param includeMetadata true면 ICC/EXIF/XMP를 함께 받는다
     * @throws IIOException 서버 불가, 손상 입력, 상한 초과 등
     */
    GetFeaturesResponse getFeatures(byte[] input, boolean includeMetadata) throws IIOException {
        return withBusyRetry("GetFeatures", ms -> getFeaturesOnce(input, includeMetadata, ms));
    }

    private GetFeaturesResponse getFeaturesOnce(byte[] input, boolean includeMetadata, long deadlineMs)
            throws IIOException {
        AtomicReference<GetFeaturesResponse> result = new AtomicReference<>();
        StreamingCall<GetFeaturesRequest, GetFeaturesResponse> call = new StreamingCall<>(result::set, deadlineMs);
        stub(deadlineMs).getFeatures(call);
        GetFeaturesRequest first = GetFeaturesRequest.newBuilder()
                .setOptions(GetFeaturesOptions.newBuilder().setInputBytes(input.length).setIncludeMetadata(includeMetadata))
                .build();
        run(call, "GetFeatures", first, chunksOf(input), b -> GetFeaturesRequest.newBuilder().setWebpData(b).build());
        if (result.get() == null) {
            throw new IIOException("WebP server returned no GetFeatures response");
        }
        return result.get();
    }

    /** Decode 응답을 받는 쪽. 헤더가 먼저 한 번, 그 뒤 픽셀 조각이 온다. gRPC 스레드에서 순서대로 불린다. */
    interface FrameReceiver {
        /** 헤더를 검증하고 버퍼를 준비한다. 상한을 넘으면 IIOException을 던져 호출을 취소한다. */
        void header(FrameHeader header) throws Exception;

        /** 픽셀의 다음 조각. */
        void pixels(ByteString chunk) throws Exception;

        /** 모든 조각을 받은 뒤 합계를 검증한다. */
        void finish() throws IIOException;
    }

    /**
     * Decode: 정지 이미지 또는 애니메이션의 합성된 프레임 하나를 받는다.
     *
     * @throws IIOException 서버 불가, 손상 입력, 상한 초과, 응답 검증 실패 등
     */
    void decode(byte[] input, DecodeOptions options, FrameReceiver receiver) throws IIOException {
        // "server busy"는 응답을 보내기 전의 거부라 수신기는 아직 아무것도 받지 않았다. 그래서 같은 수신기로 다시 시도한다.
        withBusyRetry("Decode", ms -> {
            decodeOnce(input, options, receiver, ms);
            return null;
        });
        receiver.finish();
    }

    private void decodeOnce(byte[] input, DecodeOptions options, FrameReceiver receiver, long deadlineMs)
            throws IIOException {
        StreamingCall<DecodeRequest, DecodeResponse> call = new StreamingCall<>(response -> {
            switch (response.getPayloadCase()) {
                case HEADER -> receiver.header(response.getHeader());
                case PIXELS -> receiver.pixels(response.getPixels());
                default -> throw new IIOException("WebP server sent a Decode response without payload");
            }
        }, deadlineMs);
        stub(deadlineMs).decode(call);
        DecodeRequest first = DecodeRequest.newBuilder().setOptions(options.toBuilder().setInputBytes(input.length)).build();
        run(call, "Decode", first, chunksOf(input), b -> DecodeRequest.newBuilder().setWebpData(b).build());
    }

    /** Encode 결과를 받는 쪽. 헤더(전체 크기) 다음에 WebP 조각이 온다. gRPC 스레드에서 순서대로 불린다. */
    interface EncodedReceiver {
        /** 결과 헤더. */
        void header(EncodedHeader header) throws Exception;

        /** 결과 WebP 파일의 다음 조각. */
        void data(ByteString chunk) throws Exception;
    }

    /** Encode에 보낼 RGBA8 조각을 처음부터 만든다. 혼잡 재시도 때 다시 부른다. */
    @FunctionalInterface
    interface PixelSource {
        /** 위쪽 행부터 차례로 RGBA8 조각을 만드는 새 반복자. 서버가 받을 준비가 될 때마다 하나씩 꺼낸다. */
        Iterator<ByteString> open() throws IIOException;
    }

    /**
     * Encode: RGBA8 픽셀 조각을 보내고 WebP 파일을 받는다.
     *
     * @param options 크기, 픽셀 포맷, 품질 설정
     * @param pixels 픽셀 조각을 만드는 쪽. 혼잡 재시도 때 처음부터 다시 만든다
     * @throws IIOException 서버 불가, 잘못된 옵션, 상한 초과 등
     */
    void encode(EncodeOptions options, PixelSource pixels, EncodedReceiver receiver) throws IIOException {
        withBusyRetry("Encode", ms -> {
            encodeOnce(options, pixels.open(), receiver, ms);
            return null;
        });
    }

    private void encodeOnce(EncodeOptions options, Iterator<ByteString> pixelChunks, EncodedReceiver receiver,
            long deadlineMs) throws IIOException {
        StreamingCall<EncodeRequest, EncodeResponse> call = new StreamingCall<>(response -> {
            switch (response.getPayloadCase()) {
                case HEADER -> receiver.header(response.getHeader());
                case WEBP_DATA -> receiver.data(response.getWebpData());
                default -> throw new IIOException("WebP server sent an Encode response without payload");
            }
        }, deadlineMs);
        stub(deadlineMs).encode(call);
        EncodeRequest first = EncodeRequest.newBuilder().setOptions(options).build();
        run(call, "Encode", first, pixelChunks, b -> EncodeRequest.newBuilder().setPixels(b).build());
    }

    /**
     * DecodeAnimation 스트림을 연다(ADR-0014 순차 읽기). 입력을 보내고 첫 메시지(특성)까지 받은 상태로 돌려준다.
     * 호출자는 다 쓰면 {@link AnimationStream#close}한다.
     *
     * @throws IIOException 서버 불가, 손상 입력, 상한 초과 등
     */
    AnimationStream openDecodeAnimation(byte[] input, long maxPixels) throws IIOException {
        return withBusyRetry("DecodeAnimation", ms -> openDecodeAnimationOnce(input, maxPixels, ms));
    }

    private AnimationStream openDecodeAnimationOnce(byte[] input, long maxPixels, long deadlineMs) throws IIOException {
        AnimationStream stream = new AnimationStream(config, deadlineMs);
        stub(deadlineMs).decodeAnimation(stream);
        DecodeAnimationOptions.Builder options = DecodeAnimationOptions.newBuilder()
                .setInputBytes(input.length)
                .setPixelFormat(PixelFormat.PIXEL_FORMAT_RGBA8);
        if (maxPixels > 0) {
            options.setMaxPixels(maxPixels);
        }
        Iterator<ByteString> chunks = chunksOf(input);
        DecodeAnimationRequest first = DecodeAnimationRequest.newBuilder().setOptions(options).build();
        Iterator<DecodeAnimationRequest> messages = new Iterator<>() {
            private boolean sentFirst;

            @Override
            public boolean hasNext() {
                return !sentFirst || chunks.hasNext();
            }

            @Override
            public DecodeAnimationRequest next() {
                if (!sentFirst) {
                    sentFirst = true;
                    return first;
                }
                return DecodeAnimationRequest.newBuilder().setWebpData(chunks.next()).build();
            }
        };
        try {
            stream.start(messages);
        } catch (IIOException e) {
            stream.close();
            throw e;
        }
        return stream;
    }

    /** 진행 중인 EncodeAnimation 업로드(ImageIO 시퀀스 쓰기 한 번). 한 스레드에서 쓴다. */
    final class AnimationUpload {
        private final StreamingCall<EncodeAnimationRequest, EncodeAnimationResponse> call;
        private boolean open;

        private AnimationUpload(StreamingCall<EncodeAnimationRequest, EncodeAnimationResponse> call) {
            this.call = call;
        }

        private void send(EncodeAnimationRequest message) throws IIOException {
            if (!open) {
                return;
            }
            try {
                open = call.send(message);
            } catch (InterruptedException e) {
                call.cancel("interrupted");
                Thread.currentThread().interrupt();
                throw new IIOException("Interrupted during WebP EncodeAnimation", e);
            }
        }

        /** 프레임 하나(표시 시간 + 캔버스 RGBA8 조각들)를 보낸다. 서버가 이미 실패했으면 보내지 않는다(결과는 finish에서). */
        void frame(int durationMs, Iterator<ByteString> pixels) throws IIOException {
            send(EncodeAnimationRequest.newBuilder().setFrame(EncodeFrame.newBuilder().setDurationMs(durationMs)).build());
            while (open && pixels.hasNext()) {
                send(EncodeAnimationRequest.newBuilder().setPixels(pixels.next()).build());
            }
        }

        /** 요청 스트림을 닫고 결과(수신기에 전달됨)를 기다린다. */
        void finish() throws IIOException {
            if (open) {
                call.halfClose();
            }
            Throwable error;
            try {
                error = call.await();
            } catch (InterruptedException e) {
                call.cancel("interrupted");
                Thread.currentThread().interrupt();
                throw new IIOException("Interrupted during WebP EncodeAnimation", e);
            }
            if (error != null) {
                throw GrpcErrors.toIIOException(error, "EncodeAnimation", config);
            }
        }

        /** 업로드를 취소한다(abort, dispose). */
        void cancel() {
            call.cancel("writer aborted");
        }
    }

    /**
     * EncodeAnimation을 시작하고 options를 보낸다. 프레임은 {@link AnimationUpload#frame}으로 보낸다.
     *
     * @throws IIOException 서버 불가 등
     */
    AnimationUpload startEncodeAnimation(EncodeAnimationOptions options, EncodedReceiver receiver) throws IIOException {
        StreamingCall<EncodeAnimationRequest, EncodeAnimationResponse> call = new StreamingCall<>(response -> {
            switch (response.getPayloadCase()) {
                case HEADER -> receiver.header(response.getHeader());
                case WEBP_DATA -> receiver.data(response.getWebpData());
                default -> throw new IIOException("WebP server sent an EncodeAnimation response without payload");
            }
        }, config.deadlineMs());
        stub(config.deadlineMs()).encodeAnimation(call);
        AnimationUpload upload = new AnimationUpload(call);
        upload.open = true;
        upload.send(EncodeAnimationRequest.newBuilder().setOptions(options).build());
        return upload;
    }

    /** 요청 스트림을 보내고 응답이 끝날 때까지 기다린다. 실패는 IIOException으로 바꾼다. */
    private <Req> void run(
            StreamingCall<Req, ?> call, String rpc, Req first, Iterator<ByteString> data, Function<ByteString, Req> wrap)
            throws IIOException {
        try {
            // 첫 메시지(options) → 데이터 조각 → half-close. 서버가 먼저 끝내면(오류) 보내기를 멈춘다.
            boolean open = call.send(first);
            while (open && data.hasNext()) {
                open = call.send(wrap.apply(data.next()));
            }
            if (open) {
                call.halfClose();
            }
            Throwable error = call.await();
            if (error != null) {
                throw GrpcErrors.toIIOException(error, rpc, config);
            }
        } catch (InterruptedException e) {
            call.cancel("interrupted");
            Thread.currentThread().interrupt();
            throw new IIOException("Interrupted during WebP " + rpc, e);
        } catch (RuntimeException e) {
            call.cancel("client error");
            throw GrpcErrors.toIIOException(e, rpc, config);
        }
    }

    /** 바이트 배열을 조각으로 나누는 반복자. 복사하지 않고 감싼다(호출이 끝날 때까지 배열을 바꾸지 않는다). */
    private Iterator<ByteString> chunksOf(byte[] input) throws IIOException {
        int chunk = chunkBytes();
        return new Iterator<>() {
            private int offset;

            @Override
            public boolean hasNext() {
                return offset < input.length;
            }

            @Override
            public ByteString next() {
                int len = Math.min(chunk, input.length - offset);
                ByteString b = UnsafeByteOperations.unsafeWrap(input, offset, len);
                offset += len;
                return b;
            }
        };
    }
}
