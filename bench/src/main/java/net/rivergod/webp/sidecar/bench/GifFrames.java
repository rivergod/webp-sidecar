package net.rivergod.webp.sidecar.bench;

import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.metadata.IIOMetadata;
import javax.imageio.stream.ImageInputStream;
import org.w3c.dom.Node;

/**
 * GIF를 JDK ImageIO GIF 리더로 읽어 프레임마다 캔버스에 합성한다(벤치마크 전용).
 *
 * <p>JDK GIF 리더의 {@code read(i)}는 프레임 원본(프레임 사각형 크기, 바뀌지 않은 픽셀은 투명)을 돌려준다. 이 플러그인의
 * {@code writeToSequence}는 캔버스 전체 크기의 완성된 프레임을 받으므로, GIF → WebP 변환 앱은 이 합성을 직접 해야 한다.
 * disposal(GIF89a §23): 0·1은 그대로 두고, 2는 프레임 영역을 투명으로 지우고, 3은 그리기 전 상태로 되돌린다.
 */
public final class GifFrames {

    /**
     * 합성한 애니메이션.
     *
     * @param width 캔버스 너비, 단위 픽셀
     * @param height 캔버스 높이, 단위 픽셀
     * @param loopCount NETSCAPE2.0 반복 횟수(0 = 무한), 확장이 없으면 1
     * @param frames 캔버스 크기 TYPE_INT_ARGB 프레임들
     * @param delaysMs 프레임별 표시 시간, 단위 ms
     */
    public record Animation(int width, int height, int loopCount, List<BufferedImage> frames, int[] delaysMs) {}

    private GifFrames() {}

    /**
     * GIF 파일을 읽어 합성한다.
     *
     * @param path GIF 파일
     * @return 합성한 프레임들
     * @throws IOException 읽기 실패
     */
    public static Animation read(Path path) throws IOException {
        ImageReader reader = ImageIO.getImageReadersByFormatName("gif").next();
        try (ImageInputStream in = ImageIO.createImageInputStream(path.toFile())) {
            reader.setInput(in, false, false);
            Node stream = reader.getStreamMetadata().getAsTree("javax_imageio_gif_stream_1.0");
            Node screen = child(stream, "LogicalScreenDescriptor");
            int width = Integer.parseInt(attr(screen, "logicalScreenWidth"));
            int height = Integer.parseInt(attr(screen, "logicalScreenHeight"));
            int n = reader.getNumImages(true);
            BufferedImage canvas = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
            List<BufferedImage> frames = new ArrayList<>(n);
            int[] delays = new int[n];
            int loop = 1;
            for (int i = 0; i < n; i++) {
                IIOMetadata md = reader.getImageMetadata(i);
                Node root = md.getAsTree("javax_imageio_gif_image_1.0");
                Node desc = child(root, "ImageDescriptor");
                int x = Integer.parseInt(attr(desc, "imageLeftPosition"));
                int y = Integer.parseInt(attr(desc, "imageTopPosition"));
                int fw = Integer.parseInt(attr(desc, "imageWidth"));
                int fh = Integer.parseInt(attr(desc, "imageHeight"));
                Node gce = child(root, "GraphicControlExtension");
                String disposal = gce == null ? "none" : attr(gce, "disposalMethod");
                delays[i] = gce == null ? 0 : Integer.parseInt(attr(gce, "delayTime")) * 10;
                Integer l = loopCount(root);
                if (l != null) {
                    loop = l;
                }
                BufferedImage previous = "restoreToPrevious".equals(disposal) ? copy(canvas) : null;
                Graphics2D g = canvas.createGraphics();
                g.drawImage(reader.read(i), x, y, null);
                g.dispose();
                frames.add(copy(canvas));
                if ("restoreToBackgroundColor".equals(disposal)) {
                    // 배경색 대신 투명으로 지운다(브라우저와 libwebp 관례).
                    int[] clear = new int[fw * fh];
                    canvas.setRGB(x, y, Math.min(fw, width - x), Math.min(fh, height - y), clear, 0, fw);
                } else if (previous != null) {
                    canvas = previous;
                }
            }
            return new Animation(width, height, loop, frames, delays);
        } finally {
            reader.dispose();
        }
    }

    private static Integer loopCount(Node imageRoot) {
        Node apps = child(imageRoot, "ApplicationExtensions");
        if (apps == null) {
            return null;
        }
        for (Node a = apps.getFirstChild(); a != null; a = a.getNextSibling()) {
            if ("NETSCAPE".equals(attr(a, "applicationID")) && a instanceof javax.imageio.metadata.IIOMetadataNode m
                    && m.getUserObject() instanceof byte[] b && b.length >= 3 && b[0] == 1) {
                return (b[1] & 0xFF) | (b[2] & 0xFF) << 8;
            }
        }
        return null;
    }

    private static BufferedImage copy(BufferedImage src) {
        BufferedImage out = new BufferedImage(src.getWidth(), src.getHeight(), BufferedImage.TYPE_INT_ARGB);
        src.copyData(out.getRaster());
        return out;
    }

    static Node child(Node parent, String name) {
        for (Node c = parent.getFirstChild(); c != null; c = c.getNextSibling()) {
            if (name.equals(c.getNodeName())) {
                return c;
            }
        }
        return null;
    }

    static String attr(Node n, String name) {
        Node a = n.getAttributes().getNamedItem(name);
        return a == null ? null : a.getNodeValue();
    }
}
