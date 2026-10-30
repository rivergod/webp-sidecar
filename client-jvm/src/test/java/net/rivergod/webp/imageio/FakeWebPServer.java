package net.rivergod.webp.imageio;

import com.google.protobuf.ByteString;
import io.grpc.Context;
import io.grpc.Deadline;
import io.grpc.Server;
import io.grpc.Status;
import io.grpc.netty.NettyServerBuilder;
import io.grpc.stub.StreamObserver;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import net.rivergod.webp.imageio.proto.v1.DecodeOptions;
import net.rivergod.webp.imageio.proto.v1.DecodeRequest;
import net.rivergod.webp.imageio.proto.v1.DecodeResponse;
import net.rivergod.webp.imageio.proto.v1.EncodeOptions;
import net.rivergod.webp.imageio.proto.v1.EncodeRequest;
import net.rivergod.webp.imageio.proto.v1.EncodeResponse;
import net.rivergod.webp.imageio.proto.v1.EncodedHeader;
import net.rivergod.webp.imageio.proto.v1.FrameHeader;
import net.rivergod.webp.imageio.proto.v1.GetInfoRequest;
import net.rivergod.webp.imageio.proto.v1.GetInfoResponse;
import net.rivergod.webp.imageio.proto.v1.Limits;
import net.rivergod.webp.imageio.proto.v1.PixelFormat;
import net.rivergod.webp.imageio.proto.v1.WebPServiceGrpc;

/**
 * 단위 테스트용 가짜 서버(127.0.0.1 TCP, 임의 포트). libwebp를 쓰지 않는다.
 *
 * <ul>
 *   <li>Decode: 입력을 다 받은 뒤 1×1 RGBA 픽셀 하나를 돌려준다.
 *   <li>Encode: 픽셀을 다 받은 뒤 4바이트 "결과 파일"을 돌려준다.
 *   <li>{@link #rejectFirst}: 처음 N번의 Decode·Encode를 options를 받자마자 실제 서버처럼 "server busy"로 거부한다.
 * </ul>
 *
 * 받은 options, 호출 데드라인(남은 ms), 받은 픽셀 바이트 수를 기록한다.
 */
final class FakeWebPServer implements AutoCloseable {

    /** 실제 서버(ADR-0017)의 거부 메시지와 같은 꼴. */
    static final String BUSY = "server busy: 8 requests queued (max_queued_requests 8); retry later";

    final AtomicInteger decodeCalls = new AtomicInteger();
    final AtomicInteger encodeCalls = new AtomicInteger();
    final AtomicReference<DecodeOptions> lastDecode = new AtomicReference<>();
    final AtomicReference<EncodeOptions> lastEncode = new AtomicReference<>();
    final AtomicLong lastDeadlineMs = new AtomicLong(-1);
    final AtomicLong lastPixelBytes = new AtomicLong(-1);
    private volatile int rejectFirst;
    private volatile Status rejectWith = Status.UNAVAILABLE.withDescription(BUSY);

    private final Server server;

    private FakeWebPServer() throws IOException {
        server = NettyServerBuilder.forAddress(new InetSocketAddress("127.0.0.1", 0))
                .addService(new Service())
                .build()
                .start();
    }

    static FakeWebPServer start() throws IOException {
        return new FakeWebPServer();
    }

    /** 플러그인 설정에 넣을 엔드포인트. */
    String endpoint() {
        return "127.0.0.1:" + server.getPort();
    }

    /** 처음 {@code n}번의 Decode·Encode를 "server busy"로 거부한다. */
    FakeWebPServer rejectFirst(int n) {
        this.rejectFirst = n;
        return this;
    }

    /** 거부할 때 쓸 상태를 바꾼다(재시도하지 않아야 하는 오류 확인용). */
    FakeWebPServer rejectWith(Status status) {
        this.rejectWith = status;
        return this;
    }

    @Override
    public void close() throws InterruptedException {
        server.shutdownNow().awaitTermination(5, TimeUnit.SECONDS);
    }

    private static long remainingMs() {
        Deadline d = Context.current().getDeadline();
        return d == null ? 0 : d.timeRemaining(TimeUnit.MILLISECONDS);
    }

    private final class Service extends WebPServiceGrpc.WebPServiceImplBase {

        @Override
        public void getInfo(GetInfoRequest request, StreamObserver<GetInfoResponse> response) {
            response.onNext(GetInfoResponse.newBuilder()
                    .setLimits(Limits.newBuilder()
                            .setMaxInputBytes(64L << 20)
                            .setMaxPixels(1L << 26)
                            .setMaxFrames(1000)
                            .setMaxMetadataBytes(2L << 20)
                            .setMaxChunkBytes(1 << 20)
                            .setMaxCompositedPixels(1L << 30))
                    .build());
            response.onCompleted();
        }

        @Override
        public StreamObserver<DecodeRequest> decode(StreamObserver<DecodeResponse> response) {
            boolean reject = decodeCalls.incrementAndGet() <= rejectFirst;
            lastDeadlineMs.set(remainingMs());
            return new StreamObserver<>() {
                private boolean done;

                @Override
                public void onNext(DecodeRequest m) {
                    if (m.hasOptions()) {
                        lastDecode.set(m.getOptions());
                        if (reject) {
                            done = true;
                            response.onError(rejectWith.asRuntimeException());
                        }
                    }
                }

                @Override
                public void onError(Throwable t) {}

                @Override
                public void onCompleted() {
                    if (done) {
                        return;
                    }
                    response.onNext(DecodeResponse.newBuilder()
                            .setHeader(FrameHeader.newBuilder()
                                    .setWidth(1)
                                    .setHeight(1)
                                    .setPixelFormat(PixelFormat.PIXEL_FORMAT_RGBA8)
                                    .setPixelBytes(4)
                                    .setHasAlpha(true))
                            .build());
                    response.onNext(DecodeResponse.newBuilder()
                            .setPixels(ByteString.copyFrom(new byte[] {10, 20, 30, (byte) 255}))
                            .build());
                    response.onCompleted();
                }
            };
        }

        @Override
        public StreamObserver<EncodeRequest> encode(StreamObserver<EncodeResponse> response) {
            boolean reject = encodeCalls.incrementAndGet() <= rejectFirst;
            lastDeadlineMs.set(remainingMs());
            return new StreamObserver<>() {
                private boolean done;
                private long pixels;

                @Override
                public void onNext(EncodeRequest m) {
                    if (m.hasOptions()) {
                        lastEncode.set(m.getOptions());
                        if (reject) {
                            done = true;
                            response.onError(rejectWith.asRuntimeException());
                        }
                    } else {
                        pixels += m.getPixels().size();
                    }
                }

                @Override
                public void onError(Throwable t) {}

                @Override
                public void onCompleted() {
                    if (done) {
                        return;
                    }
                    lastPixelBytes.set(pixels);
                    response.onNext(EncodeResponse.newBuilder()
                            .setHeader(EncodedHeader.newBuilder().setWebpBytes(4))
                            .build());
                    response.onNext(EncodeResponse.newBuilder()
                            .setWebpData(ByteString.copyFromUtf8("RIFF"))
                            .build());
                    response.onCompleted();
                }
            };
        }
    }
}
