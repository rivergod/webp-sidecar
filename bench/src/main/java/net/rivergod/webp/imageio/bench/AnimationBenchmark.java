package net.rivergod.webp.imageio.bench;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.concurrent.TimeUnit;
import javax.imageio.ImageReader;
import javax.imageio.spi.ImageReaderSpi;
import javax.imageio.stream.ImageInputStream;
import javax.imageio.stream.MemoryCacheImageInputStream;
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
import org.openjdk.jmh.infra.Blackhole;

/**
 * 애니메이션 전체 프레임 읽기 처리량(참고값, 판정 기준 아님). 입력은 사진 팬 12프레임 1024×768 lossy.
 *
 * <p>{@code getNumImages(true)} 후 {@code read(0..n-1)}을 차례로 부른다. 결과의 의미가 다르다: 이 프로젝트는 i번째 프레임까지
 * 합성한 캔버스(ADR-0014), TwelveMonkeys는 프레임 자체를 돌려준다. darkxanter는 애니메이션을 읽지 못해 뺀다(검증 테스트에서 확인).
 */
@BenchmarkMode(org.openjdk.jmh.annotations.Mode.Throughput)
@OutputTimeUnit(TimeUnit.SECONDS)
@Fork(value = 3, jvmArgsAppend = {"-Xms8g", "-Xmx8g", "--enable-native-access=ALL-UNNAMED"})
@Warmup(iterations = 5, time = 1)
@Measurement(iterations = 10, time = 1)
@State(Scope.Thread)
public class AnimationBenchmark {

    /** 비교 대상: SIDECAR 또는 TWELVEMONKEYS. */
    @Param({"SIDECAR", "TWELVEMONKEYS"})
    public String impl;

    /** SIDECAR의 서버 이름. */
    @Param({"sse2-uds"})
    public String server;

    private ImageReaderSpi spi;
    private byte[] webp;
    private int frames;

    /**
     * 입력을 읽고 한 번 전체를 읽어 프레임 수를 확인한다.
     *
     * @throws IOException 입력이나 디코딩 실패
     */
    @Setup(Level.Trial)
    public void setup() throws IOException {
        spi = switch (Decoders.Impl.of(impl)) {
            case SIDECAR -> {
                BenchServers.use(server);
                yield new net.rivergod.webp.imageio.WebPImageReaderSpi();
            }
            case TWELVEMONKEYS -> new com.twelvemonkeys.imageio.plugins.webp.WebPImageReaderSpi();
            default -> throw new IllegalArgumentException("animation not supported: " + impl);
        };
        webp = BenchInputs.animation();
        frames = BenchInputs.find(BenchInputs.Content.PHOTO, "anim", "anim_lossy_q75").get("frames").getAsInt();
        int n = readAll(null);
        if (n != frames) {
            throw new IllegalStateException(impl + " read " + n + " frames, expected " + frames);
        }
    }

    /**
     * 모든 프레임을 읽는다.
     *
     * @param bh 결과 소비
     * @return 프레임 수
     * @throws IOException 디코딩 실패
     */
    @Benchmark
    public int readAllFrames(Blackhole bh) throws IOException {
        return readAll(bh);
    }

    private int readAll(Blackhole bh) throws IOException {
        ImageReader reader = spi.createReaderInstance();
        try (ImageInputStream in = new MemoryCacheImageInputStream(new ByteArrayInputStream(webp))) {
            reader.setInput(in, false, true);
            int n = reader.getNumImages(true);
            for (int i = 0; i < n; i++) {
                var img = reader.read(i, null);
                if (bh != null) {
                    bh.consume(img);
                }
            }
            return n;
        } finally {
            reader.dispose();
        }
    }
}
