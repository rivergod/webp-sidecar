package net.rivergod.webp.imageio;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;

/** 테스트 공용: samples/manifest.json 읽기, RGBA 해시, 결정적 테스트 이미지. */
final class TestImages {

    static final Path SAMPLES = Path.of(System.getProperty("user.dir")).resolve("../samples").normalize();

    private TestImages() {}

    /** manifest의 files[] 중 경로가 prefix로 시작하는 항목. */
    static List<JsonObject> manifest(String prefix) throws IOException {
        JsonObject root = JsonParser.parseString(Files.readString(SAMPLES.resolve("manifest.json"))).getAsJsonObject();
        List<JsonObject> out = new ArrayList<>();
        for (JsonElement e : root.getAsJsonArray("files")) {
            JsonObject f = e.getAsJsonObject();
            if (f.get("path").getAsString().startsWith(prefix)) {
                out.add(f);
            }
        }
        return out;
    }

    /** BufferedImage를 RGBA8(비-premultiplied) 바이트로 만들어 SHA-256 hex를 돌려준다(manifest 기대값과 같은 형식). */
    static String rgbaSha256(BufferedImage image) {
        int w = image.getWidth();
        int h = image.getHeight();
        int[] argb = image.getRGB(0, 0, w, h, null, 0, w);
        byte[] rgba = new byte[argb.length * 4];
        for (int i = 0; i < argb.length; i++) {
            rgba[i * 4] = (byte) (argb[i] >> 16);
            rgba[i * 4 + 1] = (byte) (argb[i] >> 8);
            rgba[i * 4 + 2] = (byte) argb[i];
            rgba[i * 4 + 3] = (byte) (argb[i] >>> 24);
        }
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(rgba));
        } catch (NoSuchAlgorithmException e) {
            throw new AssertionError(e);
        }
    }

    /** 결정적인 ARGB 패턴 이미지(alpha 포함 여부 선택). */
    static BufferedImage pattern(int w, int h, boolean alpha) {
        BufferedImage img = new BufferedImage(w, h, alpha ? BufferedImage.TYPE_INT_ARGB : BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int a = alpha ? (x ^ y) & 0xff : 0xff;
                img.setRGB(x, y, (a << 24) | ((x * 255 / w) << 16) | ((y * 255 / h) << 8) | ((x + y) & 0xff));
            }
        }
        return img;
    }
}
