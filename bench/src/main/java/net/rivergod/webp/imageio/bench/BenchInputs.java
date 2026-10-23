package net.rivergod.webp.imageio.bench;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import javax.imageio.ImageIO;

/**
 * 벤치 입력 세트(samples/bench/{synthetic,photo}/index.json)를 찾는다. 세트는 bench/run.sh가 만든다(커밋하지 않음).
 *
 * <p>위치: 시스템 프로퍼티 {@code webp.bench.samples}(기본값: 작업 디렉터리 기준 {@code samples}). 파일을 읽을 때 index의
 * sha256과 대조한다.
 */
public final class BenchInputs {

    /** 이미지 내용. JMH 파라미터 값은 소문자 이름이다({@link #of}). */
    public enum Content {
        /** public domain 사진(samples/bench.lock.json). */
        PHOTO,
        /** 그래디언트 + 노이즈(bench_synth.py). */
        SYNTHETIC;

        /**
         * 파라미터 문자열(대소문자 무시)로 찾는다.
         *
         * @param name "photo" 또는 "synthetic"
         * @return 내용
         */
        public static Content of(String name) {
            return valueOf(name.toUpperCase(java.util.Locale.ROOT));
        }

        String dir() {
            return name().toLowerCase(java.util.Locale.ROOT);
        }
    }

    /** 인코딩 모드. bench_synth.py MODES와 대응한다. JMH 파라미터 값은 소문자 이름이다({@link #of}). */
    public enum Mode {
        /** cwebp -q 75, 불투명. */
        LOSSY("lossy_q75", "rgb"),
        /** cwebp -lossless (q 75, m 4), 불투명. */
        LOSSLESS("lossless", "rgb"),
        /** cwebp -q 75, RGBA 입력(ALPH 청크). */
        ALPHA("lossy_alpha_q75", "rgba");

        final String indexMode;
        final String sourceKind;

        Mode(String indexMode, String sourceKind) {
            this.indexMode = indexMode;
            this.sourceKind = sourceKind;
        }

        /**
         * 파라미터 문자열(대소문자 무시)로 찾는다.
         *
         * @param name "lossy", "lossless", "alpha"
         * @return 모드
         */
        public static Mode of(String name) {
            return valueOf(name.toUpperCase(java.util.Locale.ROOT));
        }

        /**
         * 알파 채널이 있는 모드인지.
         *
         * @return ALPHA면 true
         */
        public boolean hasAlpha() {
            return "rgba".equals(sourceKind);
        }
    }

    private BenchInputs() {}

    static Path samplesDir() {
        return Path.of(System.getProperty("webp.bench.samples", "samples"));
    }

    /**
     * 정지 이미지 WebP 바이트.
     *
     * @param content 내용
     * @param sizeClass 크기 클래스(XS, S, M, L, XL)
     * @param mode 인코딩 모드
     * @return 파일 바이트(sha256 확인 후)
     */
    public static byte[] webp(Content content, String sizeClass, Mode mode) {
        return read(find(content, sizeClass, mode.indexMode));
    }

    /**
     * 인코딩 벤치용 원본 이미지. 불투명이면 TYPE_INT_RGB, rgba면 TYPE_INT_ARGB로 맞춘다.
     *
     * @param content 내용
     * @param sizeClass 크기 클래스
     * @param mode 인코딩 모드(원본 종류를 정한다)
     * @return 원본 PNG를 읽은 이미지
     */
    public static BufferedImage source(Content content, String sizeClass, Mode mode) {
        JsonObject e = find(content, sizeClass, "source_" + mode.sourceKind);
        try {
            BufferedImage png = ImageIO.read(new java.io.ByteArrayInputStream(read(e)));
            int type = "rgba".equals(mode.sourceKind) ? BufferedImage.TYPE_INT_ARGB : BufferedImage.TYPE_INT_RGB;
            if (png.getType() == type) {
                return png;
            }
            BufferedImage out = new BufferedImage(png.getWidth(), png.getHeight(), type);
            int w = png.getWidth();
            int[] row = new int[w];
            for (int y = 0; y < png.getHeight(); y++) {
                png.getRGB(0, y, w, 1, row, 0, w);
                out.setRGB(0, y, w, 1, row, 0, w);
            }
            return out;
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }

    /**
     * 애니메이션 입력(사진 팬 12프레임, lossy q75).
     *
     * @return 파일 바이트
     */
    public static byte[] animation() {
        return read(find(Content.PHOTO, "anim", "anim_lossy_q75"));
    }

    /**
     * index 항목. 너비·높이·프레임 수 확인용.
     *
     * @param content 내용
     * @param sizeClass 크기 클래스 또는 "anim"
     * @param indexMode index의 mode 값
     * @return index.json 항목
     */
    static JsonObject find(Content content, String sizeClass, String indexMode) {
        Path index = samplesDir().resolve("bench").resolve(content.dir()).resolve("index.json");
        JsonObject root;
        try {
            root = new Gson().fromJson(Files.readString(index), JsonObject.class);
        } catch (IOException ex) {
            throw new UncheckedIOException("bench inputs missing (run bench/run.sh inputs): " + index, ex);
        }
        for (JsonElement el : root.getAsJsonArray("files")) {
            JsonObject e = el.getAsJsonObject();
            if (e.get("class").getAsString().equals(sizeClass) && e.get("mode").getAsString().equals(indexMode)) {
                return e;
            }
        }
        throw new IllegalArgumentException("no bench input " + content + "/" + sizeClass + "/" + indexMode);
    }

    private static byte[] read(JsonObject e) {
        Path p = samplesDir().resolve(e.get("path").getAsString());
        try {
            byte[] data = Files.readAllBytes(p);
            String sha = PhotoSources.sha256(data);
            if (!sha.equals(e.get("sha256").getAsString())) {
                throw new IllegalStateException("sha256 mismatch: " + p);
            }
            return data;
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }
}
