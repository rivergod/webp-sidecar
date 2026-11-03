package net.rivergod.webp.sidecar.bench;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import javax.imageio.ImageReader;
import javax.imageio.stream.MemoryCacheImageInputStream;
import org.junit.jupiter.api.Test;

/**
 * 벤치 비교 대상들이 같은 입력에서 같은 이미지를 내는지 확인한다(같은 일을 재는지). 기준은 이 프로젝트 플러그인(libwebp 1.6.0)이다.
 *
 * <ul>
 *   <li>WASM(libwebp 1.6.0)과 DARKXANTER(libwebp 내장): libwebp 디코딩이므로 모든 모드에서 픽셀이 같아야 한다.
 *   <li>TWELVEMONKEYS(독자 구현): lossless는 같아야 하고, lossy는 YUV→RGB 변환이 달라 PSNR 30 dB 이상이면 같은 이미지로 본다.
 * </ul>
 */
class DecoderAgreementTest {

    private static final String[] SIZES = {"XS", "S", "M", "L", "XL"};
    private static final double MIN_LOSSY_PSNR = 30.0;

    @Test
    void benchSetDecodesToSameImageAcrossImplementations() throws Exception {
        // Arrange
        BenchTestSupport.requireServer();
        Map<Decoders.Impl, Decoders.Decoder> decoders = new EnumMap<>(Decoders.Impl.class);
        for (Decoders.Impl i : List.of(Decoders.Impl.SIDECAR, Decoders.Impl.TWELVEMONKEYS, Decoders.Impl.DARKXANTER,
                Decoders.Impl.WASM)) {
            decoders.put(i, i.create());
        }
        List<String> failures = new ArrayList<>();
        StringBuilder md = new StringBuilder("| 내용 | 크기 | 모드 | 구현 | 결과 타입 | 픽셀 일치 | PSNR (dB) |\n|---|---|---|---|---|---|---|\n");

        // Act
        for (BenchInputs.Content c : BenchInputs.Content.values()) {
            for (String size : SIZES) {
                for (BenchInputs.Mode m : BenchInputs.Mode.values()) {
                    byte[] webp = BenchInputs.webp(c, size, m);
                    BufferedImage ref = decoders.get(Decoders.Impl.SIDECAR).decode(webp);
                    for (Map.Entry<Decoders.Impl, Decoders.Decoder> d : decoders.entrySet()) {
                        BufferedImage img = d.getValue().decode(webp);
                        double psnr = BenchTestSupport.psnr(ref, img);
                        boolean exact = psnr == Double.POSITIVE_INFINITY;
                        md.append(String.format(Locale.ROOT, "| %s | %s | %s | %s | %s | %s | %s |%n", c.dir(), size,
                                m.name().toLowerCase(Locale.ROOT), d.getKey(), BenchTestSupport.typeName(img),
                                exact ? "예" : "아니오", exact ? "∞" : String.format(Locale.ROOT, "%.2f", psnr)));
                        boolean ok = switch (d.getKey()) {
                            case TWELVEMONKEYS -> m == BenchInputs.Mode.LOSSLESS ? exact : psnr >= MIN_LOSSY_PSNR;
                            default -> exact;
                        };
                        if (!ok) {
                            failures.add(c.dir() + "/" + size + "/" + m + " " + d.getKey() + " psnr=" + psnr);
                        }
                    }
                }
            }
        }
        BenchTestSupport.writeReport("agreement.md", md.toString());

        // Assert
        assertTrue(failures.isEmpty(), () -> "decoders disagree:\n" + String.join("\n", failures));
    }

    @Test
    void darkxanterCannotReadAnimation() throws Exception {
        // Arrange: AnimationBenchmark에서 darkxanter를 뺀 근거를 남긴다.
        BenchTestSupport.requireInputs();
        byte[] anim = BenchInputs.animation();
        ImageReader reader = new com.luciad.imageio.webp.WebPImageReaderSpi().createReaderInstance();
        String outcome;

        // Act
        try (MemoryCacheImageInputStream in = new MemoryCacheImageInputStream(new ByteArrayInputStream(anim))) {
            reader.setInput(in, false, true);
            int n = reader.getNumImages(true);
            BufferedImage first = reader.read(0, null);
            outcome = "frames=" + n + ", first=" + first.getWidth() + "x" + first.getHeight();
        } catch (Exception e) {
            outcome = "error: " + e;
        } finally {
            reader.dispose();
        }
        BenchTestSupport.writeReport("darkxanter-animation.md", outcome + "\n");

        // Assert: 12프레임을 모두 읽을 수 있다면 벤치에 넣어야 한다.
        assertEquals(false, outcome.startsWith("frames=12"), outcome);
    }
}
