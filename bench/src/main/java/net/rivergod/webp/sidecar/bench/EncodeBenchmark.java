package net.rivergod.webp.sidecar.bench;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import javax.imageio.IIOImage;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.spi.ImageWriterSpi;
import javax.imageio.stream.MemoryCacheImageOutputStream;
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
 * 정지 이미지 인코딩 처리량. 판정 기준이 아니고 기록만 한다(ADR-0011: TwelveMonkeys에는 WebP writer가 없다).
 *
 * <p>{@link BufferedImage} → WebP 바이트. 설정은 두 플러그인 모두 ImageIO 표준 파라미터로 맞춘다: lossy/alpha는
 * {@code Lossy} 품질 0.75, lossless는 {@code Lossless} 품질 0.75. method는 둘 다 libwebp 기본값 4다. 이 프로젝트 서버는
 * lossless에서 {@code exact=1}(투명 영역 RGB 보존)을 쓴다. 불투명 입력에서는 차이가 없다.
 */
@BenchmarkMode(org.openjdk.jmh.annotations.Mode.Throughput)
@OutputTimeUnit(TimeUnit.SECONDS)
@Fork(value = 3, jvmArgsAppend = {"-Xms8g", "-Xmx8g", "--enable-native-access=ALL-UNNAMED"})
@Warmup(iterations = 5, time = 1)
@Measurement(iterations = 10, time = 1)
@State(Scope.Thread)
public class EncodeBenchmark {

    /** 비교 대상: SIDECAR 또는 DARKXANTER. */
    @Param({"SIDECAR", "DARKXANTER"})
    public String impl;

    /** SIDECAR의 서버 이름. */
    @Param({"sse2-uds"})
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

    private ImageWriterSpi spi;
    private BufferedImage image;
    private boolean lossless;

    /**
     * 원본 이미지를 읽고 한 번 인코딩해 본다.
     *
     * @throws IOException 입력이나 인코딩 실패
     */
    @Setup(Level.Trial)
    public void setup() throws IOException {
        Decoders.Impl i = Decoders.Impl.of(impl);
        spi = switch (i) {
            case SIDECAR -> {
                BenchServers.use(server);
                yield new net.rivergod.webp.sidecar.WebPImageWriterSpi();
            }
            case DARKXANTER -> new com.luciad.imageio.webp.WebPImageWriterSpi();
            default -> throw new IllegalArgumentException("no WebP writer: " + impl);
        };
        BenchInputs.Mode m = BenchInputs.Mode.of(mode);
        lossless = m == BenchInputs.Mode.LOSSLESS;
        image = BenchInputs.source(BenchInputs.Content.of(content), size, m);
        if (encode(spi, image, lossless).length < 12) {
            throw new IllegalStateException("empty output");
        }
    }

    /**
     * 한 번 인코딩한다.
     *
     * @return WebP 바이트
     * @throws IOException 인코딩 실패
     */
    @Benchmark
    public byte[] encode() throws IOException {
        return encode(spi, image, lossless);
    }

    /**
     * {@code ImageIO.write}처럼 writer를 새로 만들어 한 번 쓴다.
     *
     * @param spi writer SPI
     * @param image 원본
     * @param lossless true면 Lossless, 아니면 Lossy
     * @return WebP 바이트
     * @throws IOException 인코딩 실패
     */
    static byte[] encode(ImageWriterSpi spi, BufferedImage image, boolean lossless) throws IOException {
        ImageWriter writer = spi.createWriterInstance();
        ByteArrayOutputStream bytes = new ByteArrayOutputStream(1 << 16);
        try (MemoryCacheImageOutputStream out = new MemoryCacheImageOutputStream(bytes)) {
            writer.setOutput(out);
            writer.write(null, new IIOImage(image, null, null), param(writer, lossless));
        } finally {
            writer.dispose();
        }
        return bytes.toByteArray();
    }

    static ImageWriteParam param(ImageWriter writer, boolean lossless) {
        ImageWriteParam p = writer.getDefaultWriteParam();
        p.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
        String want = lossless ? "lossless" : "lossy";
        for (String t : p.getCompressionTypes()) {
            if (t.toLowerCase(Locale.ROOT).equals(want)) {
                p.setCompressionType(t);
            }
        }
        if (!want.equals(p.getCompressionType().toLowerCase(Locale.ROOT))) {
            throw new IllegalStateException("no compression type " + want + " in " + String.join(",", p.getCompressionTypes()));
        }
        p.setCompressionQuality(0.75f);
        return p;
    }
}
