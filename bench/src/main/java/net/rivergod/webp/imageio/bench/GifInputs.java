package net.rivergod.webp.imageio.bench;

import com.google.gson.GsonBuilder;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageTypeSpecifier;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.metadata.IIOMetadata;
import javax.imageio.metadata.IIOMetadataNode;
import javax.imageio.stream.ImageOutputStream;

/**
 * GIF → WebP 변환 측정용 합성 GIF를 만든다(벤치마크 전용, 커밋하지 않음).
 *
 * <p>사용법: {@code GifInputs <samples 디렉터리>}. 입력: PhotoSources가 만든 {@code bench/photo/L_1920x1080_rgb.png}.
 * 출력: {@code bench/gif/video_<w>x<h>_<프레임 수>.gif}와 {@code index.json}.
 *
 * <p>"영상을 GIF로 바꾼" 파일을 흉내 낸다: 모든 프레임이 캔버스 전체 크기이고 disposal은 doNotDispose, 프레임마다 지역
 * 팔레트(JDK GIF writer가 프레임별로 256색 양자화), 반복 무한, 지연 70 ms(약 14 fps). 내용은 사진 위를 2배 축소 창으로
 * 대각선으로 왕복하며 훑는다(프레임마다 거의 모든 픽셀이 바뀐다). 실제 변환 도구가 쓰는 "바뀌지 않은 픽셀 = 투명색"
 * 최적화는 하지 않는다(서버 작업량은 캔버스 × 프레임으로 같다).
 */
public final class GifInputs {

    /** {너비, 높이, 프레임 수}. 앞의 둘은 사용자 제공 GIF와 같은 규격, 나머지는 프레임 수 변화 측정용이다. */
    static final int[][] SETS = {
        {572, 315, 111}, {480, 270, 93}, {480, 270, 10}, {480, 270, 100}, {480, 270, 300}, {480, 270, 1000},
    };

    static final int DELAY_CS = 7;

    private GifInputs() {}

    /**
     * 명령줄 진입점.
     *
     * @param args {@code <samples 디렉터리>}
     * @throws IOException 입출력 오류
     */
    public static void main(String[] args) throws IOException {
        Path samples = Path.of(args.length > 0 ? args[0] : "samples");
        BufferedImage photo = ImageIO.read(samples.resolve("bench/photo/L_1920x1080_rgb.png").toFile());
        int sw = photo.getWidth();
        int sh = photo.getHeight();
        int[] src = photo.getRGB(0, 0, sw, sh, null, 0, sw);
        Path out = samples.resolve("bench/gif");
        Files.createDirectories(out);
        List<Map<String, Object>> files = new ArrayList<>();
        for (int[] set : SETS) {
            int w = set[0];
            int h = set[1];
            int n = set[2];
            Path path = out.resolve("video_" + w + "x" + h + "_" + n + ".gif");
            write(path, src, sw, sh, w, h, n);
            Map<String, Object> e = new TreeMap<>();
            e.put("path", samples.relativize(path).toString().replace('\\', '/'));
            e.put("width", w);
            e.put("height", h);
            e.put("frames", n);
            e.put("delay_ms", DELAY_CS * 10);
            e.put("size", Files.size(path));
            e.put("sha256", PhotoSources.sha256(Files.readAllBytes(path)));
            files.add(e);
            System.out.println("gif inputs: " + path.getFileName() + " " + Files.size(path) + " bytes");
        }
        Map<String, Object> index = new TreeMap<>();
        index.put("files", files);
        index.put("generator", "bench/src/main/java/net/rivergod/webp/imageio/bench/GifInputs.java");
        index.put("java", System.getProperty("java.vendor") + " " + System.getProperty("java.runtime.version"));
        Files.writeString(out.resolve("index.json"),
                new GsonBuilder().setPrettyPrinting().create().toJson(index) + "\n", StandardCharsets.UTF_8);
    }

    private static void write(Path path, int[] src, int sw, int sh, int w, int h, int n) throws IOException {
        ImageWriter writer = ImageIO.getImageWritersByFormatName("gif").next();
        Files.deleteIfExists(path);
        try (ImageOutputStream ios = ImageIO.createImageOutputStream(path.toFile())) {
            writer.setOutput(ios);
            ImageWriteParam param = writer.getDefaultWriteParam();
            writer.prepareWriteSequence(null);
            int rangeX = sw - 2 * w;
            int rangeY = sh - 2 * h;
            for (int i = 0; i < n; i++) {
                // 창 위치는 삼각파로 왕복한다(가로 프레임당 7 px, 세로 3 px).
                int x = triangle(i * 7, rangeX);
                int y = triangle(i * 3, rangeY);
                int[] rgb = PhotoSources.resize(src, sw, sh, x, y, 2 * w, 2 * h, w, h);
                BufferedImage frame = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
                frame.setRGB(0, 0, w, h, rgb, 0, w);
                IIOMetadata md = writer.getDefaultImageMetadata(ImageTypeSpecifier.createFromRenderedImage(frame), param);
                String format = "javax_imageio_gif_image_1.0";
                IIOMetadataNode root = (IIOMetadataNode) md.getAsTree(format);
                IIOMetadataNode gce = new IIOMetadataNode("GraphicControlExtension");
                gce.setAttribute("disposalMethod", "doNotDispose");
                gce.setAttribute("userInputFlag", "FALSE");
                gce.setAttribute("transparentColorFlag", "FALSE");
                gce.setAttribute("delayTime", Integer.toString(DELAY_CS));
                gce.setAttribute("transparentColorIndex", "0");
                root.appendChild(gce);
                if (i == 0) {
                    IIOMetadataNode apps = new IIOMetadataNode("ApplicationExtensions");
                    IIOMetadataNode app = new IIOMetadataNode("ApplicationExtension");
                    app.setAttribute("applicationID", "NETSCAPE");
                    app.setAttribute("authenticationCode", "2.0");
                    app.setUserObject(new byte[] {1, 0, 0});
                    apps.appendChild(app);
                    root.appendChild(apps);
                }
                md.setFromTree(format, root);
                writer.writeToSequence(new IIOImage(frame, null, md), param);
            }
            writer.endWriteSequence();
        } finally {
            writer.dispose();
        }
    }

    private static int triangle(int t, int range) {
        if (range <= 0) {
            return 0;
        }
        int p = t % (2 * range);
        return p <= range ? p : 2 * range - p;
    }
}
