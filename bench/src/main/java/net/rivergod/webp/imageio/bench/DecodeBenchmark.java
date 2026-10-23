package net.rivergod.webp.imageio.bench;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.util.concurrent.TimeUnit;
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
import org.openjdk.jmh.annotations.Warmup;

/**
 * 정지 이미지 디코딩 처리량(ADR-0011 판정 대상). 메모리의 WebP 바이트 → {@link BufferedImage}, 단일 스레드.
 *
 * <p>측정 조건은 ADR-0015: fork 3, warmup 5 × 1 s, 측정 10 × 1 s, throughput(ops/s). {@code server}는 SIDECAR일 때만
 * 쓰는 서버 이름이다({@link BenchServers}). 다른 비교 대상에서는 이름표일 뿐이다.
 */
@BenchmarkMode(org.openjdk.jmh.annotations.Mode.Throughput)
@OutputTimeUnit(TimeUnit.SECONDS)
@Fork(value = 3, jvmArgsAppend = {"-Xms8g", "-Xmx8g", "--enable-native-access=ALL-UNNAMED"})
@Warmup(iterations = 5, time = 1)
@Measurement(iterations = 10, time = 1)
@State(Scope.Thread)
public class DecodeBenchmark {

    /** 비교 대상({@link Decoders.Impl}). */
    @Param({"SIDECAR", "TWELVEMONKEYS", "DARKXANTER", "WASM"})
    public String impl;

    /** SIDECAR의 서버 이름. */
    @Param({"sse2-uds"})
    public String server;

    /** 이미지 내용. */
    @Param({"photo", "synthetic"})
    public String content;

    /** 크기 클래스. */
    @Param({"XS", "S", "M", "L", "XL"})
    public String size;

    /** 인코딩 모드. */
    @Param({"lossy", "lossless", "alpha"})
    public String mode;

    private Decoders.Decoder decoder;
    private byte[] webp;

    /**
     * 입력을 읽고 디코더를 만든 뒤 한 번 디코딩해 크기를 확인한다.
     *
     * @throws IOException 입력이나 디코딩 실패
     */
    @Setup(Level.Trial)
    public void setup() throws IOException {
        Decoders.Impl i = Decoders.Impl.of(impl);
        if (i == Decoders.Impl.SIDECAR) {
            BenchServers.use(server);
        }
        BenchInputs.Content c = BenchInputs.Content.of(content);
        BenchInputs.Mode m = BenchInputs.Mode.of(mode);
        webp = BenchInputs.webp(c, size, m);
        decoder = i.create();
        BufferedImage img = decoder.decode(webp);
        var e = BenchInputs.find(c, size, m.indexMode);
        if (img.getWidth() != e.get("width").getAsInt() || img.getHeight() != e.get("height").getAsInt()) {
            throw new IllegalStateException(impl + " decoded " + img.getWidth() + "x" + img.getHeight());
        }
    }

    /**
     * 한 번 디코딩한다.
     *
     * @return 디코딩한 이미지(JMH가 소비한다)
     * @throws IOException 디코딩 실패
     */
    @Benchmark
    public BufferedImage decode() throws IOException {
        return decoder.decode(webp);
    }
}
