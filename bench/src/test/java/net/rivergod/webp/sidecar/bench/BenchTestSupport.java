package net.rivergod.webp.sidecar.bench;

import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 검증 테스트 공통. 서버 엔드포인트는 {@code -Dwebp.bench.endpoint}(bench/run.sh verify가 넘긴다), 없으면 테스트를 건너뛴다.
 * 결과 표는 {@code -Dwebp.bench.report} 디렉터리에 쓴다(없으면 쓰지 않는다).
 */
final class BenchTestSupport {

    private BenchTestSupport() {}

    /** 벤치 입력 세트가 있는지 확인한다. 없으면 테스트를 건너뛴다. */
    static void requireInputs() {
        Path index = BenchInputs.samplesDir().resolve("bench/photo/index.json");
        assumeTrue(Files.isRegularFile(index), "bench inputs missing (run bench/run.sh inputs)");
    }

    /** 입력 세트와 서버가 지정됐는지 확인하고 플러그인 엔드포인트로 설정한다. 없으면 테스트를 건너뛴다. */
    static void requireServer() {
        requireInputs();
        String endpoint = System.getProperty("webp.bench.endpoint");
        assumeTrue(endpoint != null && !endpoint.isBlank(), "webp.bench.endpoint not set (run bench/run.sh verify)");
        System.setProperty("webp.sidecar.endpoint", endpoint);
    }

    /** 결과 표를 보고서 디렉터리에 쓴다. */
    static void writeReport(String name, String markdown) throws IOException {
        String dir = System.getProperty("webp.bench.report");
        if (dir == null || dir.isBlank()) {
            return;
        }
        Path p = Path.of(dir).resolve(name);
        Files.createDirectories(p.getParent());
        Files.writeString(p, markdown, StandardCharsets.UTF_8);
    }

    /**
     * 두 이미지의 PSNR(dB). RGB 세 채널과, 둘 중 하나라도 알파가 있으면 알파 채널까지 포함한다. 같으면 무한대.
     *
     * @throws IllegalArgumentException 크기가 다르면
     */
    static double psnr(BufferedImage a, BufferedImage b) {
        if (a.getWidth() != b.getWidth() || a.getHeight() != b.getHeight()) {
            throw new IllegalArgumentException("size mismatch");
        }
        boolean alpha = a.getColorModel().hasAlpha() || b.getColorModel().hasAlpha();
        int w = a.getWidth();
        int[] ra = new int[w];
        int[] rb = new int[w];
        double se = 0;
        long n = 0;
        for (int y = 0; y < a.getHeight(); y++) {
            a.getRGB(0, y, w, 1, ra, 0, w);
            b.getRGB(0, y, w, 1, rb, 0, w);
            for (int x = 0; x < w; x++) {
                for (int s = alpha ? 24 : 16; s >= 0; s -= 8) {
                    int d = ((ra[x] >>> s) & 0xFF) - ((rb[x] >>> s) & 0xFF);
                    se += (double) d * d;
                    n++;
                }
            }
        }
        if (se == 0) {
            return Double.POSITIVE_INFINITY;
        }
        return 10 * Math.log10(255.0 * 255.0 / (se / n));
    }

    /** {@code getRGB} 값(ARGB, sRGB)이 모든 픽셀에서 같은지. */
    static boolean sameArgb(BufferedImage a, BufferedImage b) {
        return psnr(a, b) == Double.POSITIVE_INFINITY;
    }

    static String typeName(BufferedImage img) {
        return switch (img.getType()) {
            case BufferedImage.TYPE_INT_ARGB -> "INT_ARGB";
            case BufferedImage.TYPE_INT_RGB -> "INT_RGB";
            case BufferedImage.TYPE_4BYTE_ABGR -> "4BYTE_ABGR";
            case BufferedImage.TYPE_3BYTE_BGR -> "3BYTE_BGR";
            case BufferedImage.TYPE_INT_ARGB_PRE -> "INT_ARGB_PRE";
            case BufferedImage.TYPE_CUSTOM -> "CUSTOM";
            default -> "type " + img.getType();
        };
    }
}
