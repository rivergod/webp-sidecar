package net.rivergod.webp.imageio.bench;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import javax.imageio.ImageIO;

/**
 * 벤치 사진 세트의 원본 PNG를 만든다(ADR-0015). public domain 사진(samples/bench.lock.json)을 받아 sha256을 확인하고,
 * 크기 클래스별로 가운데를 잘라 줄인다. 인코딩은 samples/scripts/bench_photo.py가 cwebp로 한다.
 *
 * <p>사용법: {@code PhotoSources <samples 디렉터리> [캐시 디렉터리]}. 캐시 기본값은 {@code ~/.cache/webp-imageio-bench}다.
 *
 * <p>출력({@code samples/bench/photo/}):
 *
 * <ul>
 *   <li>{@code <class>_<w>x<h>_rgb.png}, {@code _rgba.png}: rgba는 가운데가 불투명하고 가장자리로 갈수록 투명해지는 원형 알파를
 *       씌운다(잘라 낸 물체 같은 알파 분포). 크기 클래스는 bench_synth.py와 같다.
 *   <li>{@code anim/frame_NN.png}: M 크기(1024×768) 12프레임. 사진 위를 왼쪽에서 오른쪽으로 훑는다(프레임마다 전체가 바뀐다).
 *   <li>{@code sources.json}: 위 파일 목록과 sha256.
 * </ul>
 *
 * <p>축소는 면적 평균(박스 필터)을 직접 계산한다. JDK 버전에 따라 결과가 달라질 수 있는 Java2D 보간을 쓰지 않는다. JPEG 디코딩은
 * JDK 내장 리더를 쓰므로 PNG 바이트는 JDK 버전에 따라 다를 수 있다(sha256을 sources.json에 남긴다).
 */
public final class PhotoSources {

    /** 크기 클래스. bench_synth.py CLASSES와 같다(XXL 제외). */
    static final Map<String, int[]> CLASSES = new LinkedHashMap<>();

    static {
        CLASSES.put("XS", new int[] {64, 64});
        CLASSES.put("S", new int[] {256, 256});
        CLASSES.put("M", new int[] {1024, 768});
        CLASSES.put("L", new int[] {1920, 1080});
        CLASSES.put("XL", new int[] {4000, 3000});
    }

    static final int ANIM_FRAMES = 12;
    static final int ANIM_WIDTH = 1024;
    static final int ANIM_HEIGHT = 768;

    private PhotoSources() {}

    /**
     * 명령줄 진입점.
     *
     * @param args {@code <samples 디렉터리> [캐시 디렉터리]}
     * @throws Exception 다운로드, 해시 불일치, 입출력 오류
     */
    public static void main(String[] args) throws Exception {
        if (args.length < 1) {
            System.err.println("usage: PhotoSources <samples-dir> [cache-dir]");
            System.exit(2);
        }
        Path samples = Path.of(args[0]);
        Path cache = args.length > 1
                ? Path.of(args[1])
                : Path.of(System.getProperty("user.home"), ".cache", "webp-imageio-bench");
        JsonObject photo = new Gson()
                .fromJson(Files.readString(samples.resolve("bench.lock.json")), JsonObject.class)
                .getAsJsonObject("photo");
        Path jpeg = fetch(photo, cache);

        BufferedImage src = ImageIO.read(jpeg.toFile());
        if (src == null || src.getWidth() != photo.get("width").getAsInt()
                || src.getHeight() != photo.get("height").getAsInt()) {
            throw new IOException("unexpected photo: " + jpeg);
        }
        int[] argb = src.getRGB(0, 0, src.getWidth(), src.getHeight(), null, 0, src.getWidth());
        int sw = src.getWidth();
        int sh = src.getHeight();
        src = null;

        Path out = samples.resolve("bench/photo");
        deleteTree(out);
        Files.createDirectories(out.resolve("anim"));
        List<Map<String, Object>> files = new ArrayList<>();
        for (Map.Entry<String, int[]> c : CLASSES.entrySet()) {
            int w = c.getValue()[0];
            int h = c.getValue()[1];
            // 목표 종횡비로 가운데를 자른다(사진은 정사각형이므로 가로 전체를 쓰고 위아래를 자른다).
            int cw = sw;
            int ch = (int) Math.round((double) sw * h / w);
            if (ch > sh) {
                ch = sh;
                cw = (int) Math.round((double) sh * w / h);
            }
            int[] rgb = resize(argb, sw, sh, (sw - cw) / 2, (sh - ch) / 2, cw, ch, w, h);
            files.add(writePng(samples, out.resolve(c.getKey() + "_" + w + "x" + h + "_rgb.png"), rgb, w, h,
                    false, c.getKey(), "source_rgb"));
            applyRadialAlpha(rgb, w, h);
            files.add(writePng(samples, out.resolve(c.getKey() + "_" + w + "x" + h + "_rgba.png"), rgb, w, h,
                    true, c.getKey(), "source_rgba"));
            System.out.println("photo sources: " + c.getKey() + " " + w + "x" + h + " done");
        }
        // 애니메이션: 4:3 창(3072×2304, 1/3로 줄이면 1024×768)을 세로 가운데에서 가로로 옮긴다.
        int winW = ANIM_WIDTH * 3;
        int winH = ANIM_HEIGHT * 3;
        for (int i = 0; i < ANIM_FRAMES; i++) {
            int x = (int) Math.round((double) (sw - winW) * i / (ANIM_FRAMES - 1));
            int[] rgb = resize(argb, sw, sh, x, (sh - winH) / 2, winW, winH, ANIM_WIDTH, ANIM_HEIGHT);
            files.add(writePng(samples, out.resolve(String.format("anim/frame_%02d.png", i)), rgb, ANIM_WIDTH,
                    ANIM_HEIGHT, false, "anim", "anim_frame"));
        }
        Map<String, Object> index = new TreeMap<>();
        index.put("files", files);
        index.put("generator", Map.of(
                "tool", "bench/src/main/java/net/rivergod/webp/imageio/bench/PhotoSources.java",
                "java", System.getProperty("java.vendor") + " " + System.getProperty("java.runtime.version"),
                "resize", "area average (box filter), center crop"));
        index.put("photo_sha256", photo.get("sha256").getAsString());
        Gson gson = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
        Files.writeString(out.resolve("sources.json"), gson.toJson(index) + "\n", StandardCharsets.UTF_8);
        System.out.println("photo sources: " + files.size() + " files -> " + out);
    }

    /** 캐시에 있으면 해시만 확인하고, 없으면 받아서 확인한 뒤 캐시에 넣는다. */
    private static Path fetch(JsonObject photo, Path cache) throws IOException, InterruptedException {
        String expected = photo.get("sha256").getAsString();
        Path target = cache.resolve(photo.get("file").getAsString());
        if (Files.isRegularFile(target)) {
            if (sha256(Files.readAllBytes(target)).equals(expected)) {
                return target;
            }
            throw new IOException("cached photo sha256 mismatch, delete it and retry: " + target);
        }
        Files.createDirectories(cache);
        HttpClient http = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NORMAL)
                .connectTimeout(Duration.ofSeconds(30))
                .build();
        HttpRequest req = HttpRequest.newBuilder(URI.create(photo.get("url").getAsString()))
                .header("User-Agent", "webp-imageio-bench/0.1")
                .timeout(Duration.ofMinutes(5))
                .build();
        HttpResponse<byte[]> resp = http.send(req, HttpResponse.BodyHandlers.ofByteArray());
        if (resp.statusCode() != 200) {
            throw new IOException("download failed: HTTP " + resp.statusCode() + " " + req.uri());
        }
        String actual = sha256(resp.body());
        if (!actual.equals(expected)) {
            throw new IOException("photo sha256 mismatch: expected " + expected + ", got " + actual);
        }
        Path tmp = Files.createTempFile(cache, "photo", ".part");
        Files.write(tmp, resp.body());
        Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE);
        return target;
    }

    /**
     * 면적 평균 축소. 원본 사각형(x0, y0, cw, ch)을 w×h로 줄인다. 출력 픽셀마다 덮는 원본 픽셀의 면적 비율로 가중 평균한다.
     * 입력과 출력은 불투명 0xFFRRGGBB.
     */
    static int[] resize(int[] src, int sw, int sh, int x0, int y0, int cw, int ch, int w, int h) {
        if (x0 < 0 || y0 < 0 || x0 + cw > sw || y0 + ch > sh || w > cw || h > ch) {
            throw new IllegalArgumentException("bad crop/resize");
        }
        // 가로 먼저: ch × w 중간 버퍼(채널별 double).
        double[] tmp = new double[ch * w * 3];
        double sx = (double) cw / w;
        for (int y = 0; y < ch; y++) {
            int row = (y0 + y) * sw + x0;
            for (int x = 0; x < w; x++) {
                double a = x * sx;
                double b = a + sx;
                double r = 0;
                double g = 0;
                double bl = 0;
                for (int i = (int) a; i < b && i < cw; i++) {
                    double wt = Math.min(b, i + 1) - Math.max(a, i);
                    int p = src[row + i];
                    r += wt * ((p >> 16) & 0xFF);
                    g += wt * ((p >> 8) & 0xFF);
                    bl += wt * (p & 0xFF);
                }
                int o = (y * w + x) * 3;
                tmp[o] = r / sx;
                tmp[o + 1] = g / sx;
                tmp[o + 2] = bl / sx;
            }
        }
        int[] out = new int[w * h];
        double sy = (double) ch / h;
        for (int y = 0; y < h; y++) {
            double a = y * sy;
            double b = a + sy;
            for (int x = 0; x < w; x++) {
                double r = 0;
                double g = 0;
                double bl = 0;
                for (int j = (int) a; j < b && j < ch; j++) {
                    double wt = Math.min(b, j + 1) - Math.max(a, j);
                    int o = (j * w + x) * 3;
                    r += wt * tmp[o];
                    g += wt * tmp[o + 1];
                    bl += wt * tmp[o + 2];
                }
                out[y * w + x] = 0xFF000000 | clamp(r / sy) << 16 | clamp(g / sy) << 8 | clamp(bl / sy);
            }
        }
        return out;
    }

    /** 가운데 alpha 255, 정규화 반지름 0.75부터 1.0까지 선형으로 0까지 줄어든다. 모서리는 완전히 투명하다. */
    static void applyRadialAlpha(int[] argb, int w, int h) {
        double cx = (w - 1) / 2.0;
        double cy = (h - 1) / 2.0;
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                double dx = (x - cx) / Math.max(cx, 1);
                double dy = (y - cy) / Math.max(cy, 1);
                double r = Math.sqrt(dx * dx + dy * dy);
                int a = clamp(255 * (1.0 - r) / 0.25);
                int i = y * w + x;
                argb[i] = (a << 24) | (argb[i] & 0x00FFFFFF);
            }
        }
    }

    private static int clamp(double v) {
        long r = Math.round(v);
        return (int) Math.max(0, Math.min(255, r));
    }

    private static Map<String, Object> writePng(Path samples, Path path, int[] argb, int w, int h, boolean alpha,
            String cls, String mode) throws IOException {
        BufferedImage img = new BufferedImage(w, h, alpha ? BufferedImage.TYPE_INT_ARGB : BufferedImage.TYPE_INT_RGB);
        img.setRGB(0, 0, w, h, argb, 0, w);
        if (!ImageIO.write(img, "png", path.toFile())) {
            throw new IOException("no PNG writer");
        }
        Map<String, Object> e = new TreeMap<>();
        e.put("path", samples.relativize(path).toString().replace('\\', '/'));
        e.put("class", cls);
        e.put("width", w);
        e.put("height", h);
        e.put("mode", mode);
        try (InputStream in = Files.newInputStream(path)) {
            e.put("sha256", sha256(in.readAllBytes()));
        }
        return e;
    }

    static String sha256(byte[] data) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));
        } catch (NoSuchAlgorithmException e) {
            throw new AssertionError(e);
        }
    }

    private static void deleteTree(Path dir) throws IOException {
        if (!Files.exists(dir)) {
            return;
        }
        try (var walk = Files.walk(dir)) {
            for (Path p : walk.sorted((a, b) -> b.getNameCount() - a.getNameCount()).toList()) {
                Files.delete(p);
            }
        }
    }
}
