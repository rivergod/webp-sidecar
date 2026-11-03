package net.rivergod.webp.sidecar.bench;

import com.google.protobuf.ByteString;
import io.grpc.ManagedChannel;
import io.grpc.stub.StreamObserver;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import net.rivergod.webp.sidecar.proto.v1.DecodeOptions;
import net.rivergod.webp.sidecar.proto.v1.DecodeRequest;
import net.rivergod.webp.sidecar.proto.v1.DecodeResponse;
import net.rivergod.webp.sidecar.proto.v1.GetFeaturesOptions;
import net.rivergod.webp.sidecar.proto.v1.GetFeaturesRequest;
import net.rivergod.webp.sidecar.proto.v1.GetFeaturesResponse;
import net.rivergod.webp.sidecar.proto.v1.GetInfoRequest;
import net.rivergod.webp.sidecar.proto.v1.PixelFormat;
import net.rivergod.webp.sidecar.proto.v1.WebPServiceGrpc;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;
import org.openjdk.jmh.annotations.Warmup;

/**
 * 사이드카 비용 분해용 원시 gRPC 호출(ADR-0003, ADR-0004 확인 방법). 플러그인을 거치지 않고 생성 stub을 직접 쓴다.
 *
 * <ul>
 *   <li>{@link #decode}: Decode RPC 하나. 응답 픽셀(RGBA8)은 받기만 하고 버린다(변환 없음) — 서버 디코딩 + 전송.
 *   <li>{@link #getFeatures}: GetFeatures RPC 하나. 플러그인은 읽기마다 이것을 Decode 앞에 한 번 부른다.
 * </ul>
 *
 * <p>플러그인 측정값(DecodeBenchmark SIDECAR)에서 두 RPC를 빼면 클라이언트 쪽 비용(RGBA → ARGB 변환, ImageIO 처리)이 남는다.
 * 입력 조각은 1 MiB(서버 상한이 더 작으면 그 값), 수신은 gRPC 자동 흐름 제어를 쓴다.
 */
@BenchmarkMode(org.openjdk.jmh.annotations.Mode.Throughput)
@OutputTimeUnit(TimeUnit.SECONDS)
@Fork(value = 3, jvmArgsAppend = {"-Xms8g", "-Xmx8g"})
@Warmup(iterations = 5, time = 1)
@Measurement(iterations = 10, time = 1)
@State(Scope.Thread)
public class TransportBenchmark {

    /** 서버 이름({@link BenchServers}). */
    @Param({"sse2-uds", "sse2-tcp"})
    public String server;

    /** 이미지 내용. */
    @Param({"photo"})
    public String content;

    /** 크기 클래스. */
    @Param({"XS", "S", "M", "L", "XL"})
    public String size;

    /** 인코딩 모드. */
    @Param({"lossy", "lossless", "alpha"})
    public String mode;

    private ManagedChannel channel;
    private WebPServiceGrpc.WebPServiceStub stub;
    private byte[] webp;
    private int chunk;
    private long expectedPixelBytes;

    /** 채널을 열고 입력을 읽는다. */
    @Setup(Level.Trial)
    public void setup() throws Exception {
        channel = BenchServers.channel(BenchServers.endpoint(server));
        stub = WebPServiceGrpc.newStub(channel);
        int serverChunk = WebPServiceGrpc.newBlockingStub(channel)
                .getInfo(GetInfoRequest.getDefaultInstance())
                .getLimits()
                .getMaxChunkBytes();
        chunk = Math.min(serverChunk, 1024 * 1024);
        BenchInputs.Content c = BenchInputs.Content.of(content);
        BenchInputs.Mode m = BenchInputs.Mode.of(mode);
        webp = BenchInputs.webp(c, size, m);
        var e = BenchInputs.find(c, size, m.indexMode);
        expectedPixelBytes = 4L * e.get("width").getAsInt() * e.get("height").getAsInt();
        if (decode() != expectedPixelBytes) {
            throw new IllegalStateException("unexpected pixel byte count");
        }
    }

    /** 채널을 닫는다. */
    @TearDown(Level.Trial)
    public void tearDown() throws InterruptedException {
        channel.shutdownNow().awaitTermination(5, TimeUnit.SECONDS);
    }

    /**
     * Decode RPC 하나. 받은 픽셀 바이트 수를 돌려준다.
     *
     * @return 픽셀 바이트 수
     * @throws Exception RPC 실패
     */
    @Benchmark
    public long decode() throws Exception {
        CompletableFuture<Long> done = new CompletableFuture<>();
        StreamObserver<DecodeRequest> req = stub.decode(new StreamObserver<DecodeResponse>() {
            long bytes;

            @Override
            public void onNext(DecodeResponse r) {
                if (r.hasPixels()) {
                    bytes += r.getPixels().size();
                }
            }

            @Override
            public void onError(Throwable t) {
                done.completeExceptionally(t);
            }

            @Override
            public void onCompleted() {
                done.complete(bytes);
            }
        });
        req.onNext(DecodeRequest.newBuilder()
                .setOptions(DecodeOptions.newBuilder()
                        .setInputBytes(webp.length)
                        .setPixelFormat(PixelFormat.PIXEL_FORMAT_RGBA8))
                .build());
        for (int off = 0; off < webp.length; off += chunk) {
            req.onNext(DecodeRequest.newBuilder()
                    .setWebpData(ByteString.copyFrom(webp, off, Math.min(chunk, webp.length - off)))
                    .build());
        }
        req.onCompleted();
        return get(done);
    }

    /**
     * GetFeatures RPC 하나(메타데이터 없음).
     *
     * @return 너비(JMH가 소비한다)
     * @throws Exception RPC 실패
     */
    @Benchmark
    public int getFeatures() throws Exception {
        CompletableFuture<GetFeaturesResponse> done = new CompletableFuture<>();
        StreamObserver<GetFeaturesRequest> req = stub.getFeatures(new StreamObserver<GetFeaturesResponse>() {
            GetFeaturesResponse last;

            @Override
            public void onNext(GetFeaturesResponse r) {
                last = r;
            }

            @Override
            public void onError(Throwable t) {
                done.completeExceptionally(t);
            }

            @Override
            public void onCompleted() {
                done.complete(last);
            }
        });
        req.onNext(GetFeaturesRequest.newBuilder()
                .setOptions(GetFeaturesOptions.newBuilder().setInputBytes(webp.length).setIncludeMetadata(false))
                .build());
        for (int off = 0; off < webp.length; off += chunk) {
            req.onNext(GetFeaturesRequest.newBuilder()
                    .setWebpData(ByteString.copyFrom(webp, off, Math.min(chunk, webp.length - off)))
                    .build());
        }
        req.onCompleted();
        return get(done).getFeatures().getWidth();
    }

    private static <T> T get(CompletableFuture<T> f) throws Exception {
        try {
            return f.get(60, TimeUnit.SECONDS);
        } catch (ExecutionException e) {
            throw e.getCause() instanceof Exception ex ? ex : e;
        }
    }
}
