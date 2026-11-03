package net.rivergod.webp.sidecar;

import java.util.Locale;
import java.util.Objects;
import javax.imageio.ImageTypeSpecifier;
import javax.imageio.ImageWriter;
import javax.imageio.spi.ImageWriterSpi;
import javax.imageio.stream.ImageOutputStream;

/**
 * WebP {@link ImageWriterSpi}. 인코딩은 libwebp gRPC 사이드카 서버가 한다(ADR-0001). 설정 방법(시스템 프로퍼티,
 * 환경변수, {@link WebPClientConfig})은 {@link WebPImageReaderSpi}와 같다. 스레드 안전하다.
 */
public final class WebPImageWriterSpi extends ImageWriterSpi {

    private final WebPClientConfig config;

    /** ImageIO 레지스트리가 인자 없이 만든다. 설정은 시스템 프로퍼티 → 환경변수 → 기본값이다. */
    public WebPImageWriterSpi() {
        this(WebPClientConfig.EMPTY);
    }

    /**
     * 등록 단위 설정을 가진 SPI를 만든다(ADR-0020). 비운 항목은 시스템 프로퍼티 → 환경변수 → 기본값으로 채운다.
     * 수동 등록하면 자동 등록된 SPI를 대체한다.
     *
     * @param config 이 SPI가 만드는 writer의 설정
     * @throws NullPointerException {@code config}가 null이면
     */
    public WebPImageWriterSpi(WebPClientConfig config) {
        super(WebPImageReaderSpi.VENDOR, WebPImageReaderSpi.VERSION, WebPImageReaderSpi.NAMES,
                WebPImageReaderSpi.SUFFIXES, WebPImageReaderSpi.MIME_TYPES, "net.rivergod.webp.sidecar.WebPImageWriter",
                new Class<?>[] {ImageOutputStream.class}, new String[] {WebPImageReaderSpi.class.getName()},
                false, WebPStreamMetadataFormat.NATIVE_FORMAT_NAME, WebPStreamMetadataFormat.class.getName(), null, null,
                true, WebPStreamMetadataFormat.NATIVE_FORMAT_NAME, WebPImageMetadataFormat.class.getName(), null, null);
        this.config = Objects.requireNonNull(config, "config");
    }

    /** 등록 단위 설정. */
    WebPClientConfig config() {
        return config;
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
     * @param extension {@link WebPClientConfig}면 그 writer의 설정으로 쓰고, 비운 항목은 이 SPI의 설정에서 채운다(ADR-0020).
     *     다른 값이나 null이면 이 SPI의 설정만 쓴다
     * @return 새 {@link ImageWriter}
     */
    @Override
    public ImageWriter createWriterInstance(Object extension) {
        WebPClientConfig writerConfig = extension instanceof WebPClientConfig c ? c.orElse(config) : config;
        return new WebPImageWriter(this, writerConfig);
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
