package net.rivergod.webp.imageio.bench;

import java.awt.image.BufferedImage;
import java.awt.image.DataBufferInt;
import java.util.SplittableRandom;
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
 * 클라이언트 픽셀 변환 비용(ADR-0004 확인 방법): RGBA8 바이트 → TYPE_INT_ARGB {@link BufferedImage}. 대상 이미지 할당을
 * 포함한다(플러그인도 읽을 때마다 새 이미지를 만든다). 픽셀 값은 결과에 영향이 없으므로 난수로 채운다.
 */
@BenchmarkMode(org.openjdk.jmh.annotations.Mode.Throughput)
@OutputTimeUnit(TimeUnit.SECONDS)
@Fork(value = 3, jvmArgsAppend = {"-Xms8g", "-Xmx8g"})
@Warmup(iterations = 5, time = 1)
@Measurement(iterations = 10, time = 1)
@State(Scope.Thread)
public class ConversionBenchmark {

    /** 크기 클래스. */
    @Param({"XS", "S", "M", "L", "XL"})
    public String size;

    private byte[] rgba;
    private int width;
    private int height;

    /** 입력 버퍼를 만든다. */
    @Setup(Level.Trial)
    public void setup() {
        int[] wh = PhotoSources.CLASSES.get(size);
        width = wh[0];
        height = wh[1];
        rgba = new byte[width * height * 4];
        new SplittableRandom(1).nextBytes(rgba);
    }

    /**
     * 새 이미지에 변환한다.
     *
     * @return 변환한 이미지
     */
    @Benchmark
    public BufferedImage rgbaToArgbImage() {
        BufferedImage img = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        Decoders.rgbaToArgb(rgba, ((DataBufferInt) img.getRaster().getDataBuffer()).getData());
        return img;
    }
}
