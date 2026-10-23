package net.rivergod.webp.imageio;

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
import net.rivergod.webp.imageio.proto.v1.DecodeOptions;
import net.rivergod.webp.imageio.proto.v1.DecodeRequest;
import net.rivergod.webp.imageio.proto.v1.DecodeResponse;
import net.rivergod.webp.imageio.proto.v1.EncodeOptions;
import net.rivergod.webp.imageio.proto.v1.EncodeRequest;
import net.rivergod.webp.imageio.proto.v1.EncodeResponse;
import net.rivergod.webp.imageio.proto.v1.EncodedHeader;
import net.rivergod.webp.imageio.proto.v1.FrameHeader;
import net.rivergod.webp.imageio.proto.v1.GetFeaturesOptions;
import net.rivergod.webp.imageio.proto.v1.GetFeaturesRequest;
import net.rivergod.webp.imageio.proto.v1.GetFeaturesResponse;
import net.rivergod.webp.imageio.proto.v1.GetInfoRequest;
import net.rivergod.webp.imageio.proto.v1.Limits;
import net.rivergod.webp.imageio.proto.v1.WebPServiceGrpc;

/**
 * webp.v1 RPC를 동기 호출로 감싼다. 스트리밍 규칙은 docs/design/grpc-api.md §2를 따른다. 실패는 모두
 * {@link IIOException}으로 바꾼다({@link GrpcErrors}). 채널은 엔드포인트별로 공유하고, 인스턴스는 가볍다.
 * 한 인스턴스를 여러 스레드에서 써도 되지만 각 호출은 호출한 스레드를 막는다.
 */
final class WebPClient {

    /** 클라이언트가 보내는 조각의 최대 크기. 서버 상한이 더 작으면 그 값을 쓴다. 단위: 바이트. (ADR-0003) */
    static final int DEFAULT_CHUNK_BYTES = 1024 * 1024;

    private static final ConcurrentMap<String, Limits> LIMITS = new ConcurrentHashMap<>();

    private final ClientConfig config;
    private final ManagedChannel channel;

    private WebPClient(ClientConfig config) {
        this.config = config;
        this.channel = WebPChannels.forEndpoint(config.endpoint());
    }

    /**
     * 현재 환경의 설정으로 클라이언트를 만든다.
     *
     * @throws IIOException 설정 값이 잘못됐으면
     */
    static WebPClient fromEnvironment() throws IIOException {
        try {
            return new WebPClient(ClientConfig.fromEnvironment());
        } catch (IllegalArgumentException e) {
            throw new IIOException("Invalid WebP client configuration: " + e.getMessage(), e);
        }
    }

    ClientConfig config() {
        return config;
    }

    private WebPServiceGrpc.WebPServiceStub stub() {
        return WebPServiceGrpc.newStub(channel).withDeadlineAfter(config.deadlineMs(), TimeUnit.MILLISECONDS);
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
        AtomicReference<GetFeaturesResponse> result = new AtomicReference<>();
        StreamingCall<GetFeaturesRequest, GetFeaturesResponse> call =
                new StreamingCall<>(result::set, config.deadlineMs());
        stub().getFeatures(call);
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
        StreamingCall<DecodeRequest, DecodeResponse> call = new StreamingCall<>(response -> {
            switch (response.getPayloadCase()) {
                case HEADER -> receiver.header(response.getHeader());
                case PIXELS -> receiver.pixels(response.getPixels());
                default -> throw new IIOException("WebP server sent a Decode response without payload");
            }
        }, config.deadlineMs());
        stub().decode(call);
        DecodeRequest first = DecodeRequest.newBuilder().setOptions(options.toBuilder().setInputBytes(input.length)).build();
        run(call, "Decode", first, chunksOf(input), b -> DecodeRequest.newBuilder().setWebpData(b).build());
        receiver.finish();
    }

    /** Encode 결과를 받는 쪽. 헤더(전체 크기) 다음에 WebP 조각이 온다. gRPC 스레드에서 순서대로 불린다. */
    interface EncodedReceiver {
        /** 결과 헤더. */
        void header(EncodedHeader header) throws Exception;

        /** 결과 WebP 파일의 다음 조각. */
        void data(ByteString chunk) throws Exception;
    }

    /**
     * Encode: RGBA8 픽셀 조각을 보내고 WebP 파일을 받는다.
     *
     * @param options 크기, 픽셀 포맷, 품질 설정
     * @param pixelChunks 위쪽 행부터 차례로 RGBA8 조각을 만든다. 서버가 받을 준비가 될 때마다 하나씩 꺼낸다
     * @throws IIOException 서버 불가, 잘못된 옵션, 상한 초과 등
     */
    void encode(EncodeOptions options, Iterator<ByteString> pixelChunks, EncodedReceiver receiver) throws IIOException {
        StreamingCall<EncodeRequest, EncodeResponse> call = new StreamingCall<>(response -> {
            switch (response.getPayloadCase()) {
                case HEADER -> receiver.header(response.getHeader());
                case WEBP_DATA -> receiver.data(response.getWebpData());
                default -> throw new IIOException("WebP server sent an Encode response without payload");
            }
        }, config.deadlineMs());
        stub().encode(call);
        EncodeRequest first = EncodeRequest.newBuilder().setOptions(options).build();
        run(call, "Encode", first, pixelChunks, b -> EncodeRequest.newBuilder().setPixels(b).build());
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
