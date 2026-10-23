package net.rivergod.webp.imageio;

import java.util.Locale;
import javax.imageio.ImageTypeSpecifier;
import javax.imageio.ImageWriter;
import javax.imageio.spi.ImageWriterSpi;
import javax.imageio.stream.ImageOutputStream;

/**
 * WebP {@link ImageWriterSpi}. 인코딩은 libwebp gRPC 사이드카 서버가 한다(ADR-0001). 설정은
 * {@link WebPImageReaderSpi}와 같다. 스레드 안전하다.
 */
public final class WebPImageWriterSpi extends ImageWriterSpi {

    /** ImageIO 레지스트리가 인자 없이 만든다. */
    public WebPImageWriterSpi() {
        super(WebPImageReaderSpi.VENDOR, WebPImageReaderSpi.VERSION, WebPImageReaderSpi.NAMES,
                WebPImageReaderSpi.SUFFIXES, WebPImageReaderSpi.MIME_TYPES, "net.rivergod.webp.imageio.WebPImageWriter",
                new Class<?>[] {ImageOutputStream.class}, new String[] {WebPImageReaderSpi.class.getName()},
                false, WebPStreamMetadataFormat.NATIVE_FORMAT_NAME, WebPStreamMetadataFormat.class.getName(), null, null,
                true, WebPStreamMetadataFormat.NATIVE_FORMAT_NAME, WebPImageMetadataFormat.class.getName(), null, null);
    }

    /**
     * 모든 이미지 타입을 받는다. 픽셀은 {@code BufferedImage#getRGB}로 sRGB 비-premultiplied ARGB로 바꿔 보낸다.
     *
     * @param type 이미지 타입
     * @return 항상 true
     */
    @Override
    public boolean canEncodeImage(ImageTypeSpecifier type) {
        return true;
    }

    /**
     * 새 writer를 만든다. writer는 스레드 안전하지 않다(ImageIO 규약).
     *
     * @param extension 쓰지 않는다
     * @return 새 {@link ImageWriter}
     */
    @Override
    public ImageWriter createWriterInstance(Object extension) {
        return new WebPImageWriter(this);
    }

    /**
     * 사람이 읽는 설명.
     *
     * @param locale 무시한다(영어만 제공)
     * @return 설명 문자열
     */
    @Override
    public String getDescription(Locale locale) {
        return "WebP image writer (libwebp gRPC sidecar)";
    }
}
