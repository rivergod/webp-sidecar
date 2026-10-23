package net.rivergod.webp.imageio;

import java.io.IOException;
import java.util.Locale;
import javax.imageio.ImageReader;
import javax.imageio.spi.ImageReaderSpi;
import javax.imageio.stream.ImageInputStream;

/**
 * WebP {@link ImageReaderSpi}. 디코딩은 libwebp gRPC 사이드카 서버가 한다(ADR-0001). JNI를 쓰지 않는다.
 *
 * <p>서버 엔드포인트와 데드라인은 시스템 프로퍼티 {@code webp.imageio.endpoint}, {@code webp.imageio.deadline-ms},
 * {@code webp.imageio.max-pixels}(또는 같은 이름의 환경변수 {@code WEBP_IMAGEIO_*})로 정한다. 기본 엔드포인트는
 * {@code unix:///run/webp-imageio/webp.sock}이다.
 *
 * <p>ImageIO 레지스트리가 classpath({@code META-INF/services}) 또는 module path({@code module-info}의
 * {@code provides})에서 찾아 등록한다. 스레드 안전하다.
 */
public final class WebPImageReaderSpi extends ImageReaderSpi {

    static final String VENDOR = "rivergod";
    static final String VERSION = "0.1.0";
    static final String[] NAMES = {"webp", "WEBP", "WebP"};
    static final String[] SUFFIXES = {"webp"};
    static final String[] MIME_TYPES = {"image/webp"};

    /** ImageIO 레지스트리가 인자 없이 만든다. */
    public WebPImageReaderSpi() {
        super(VENDOR, VERSION, NAMES, SUFFIXES, MIME_TYPES, "net.rivergod.webp.imageio.WebPImageReader",
                new Class<?>[] {ImageInputStream.class}, new String[] {WebPImageWriterSpi.class.getName()},
                false, WebPStreamMetadataFormat.NATIVE_FORMAT_NAME, WebPStreamMetadataFormat.class.getName(), null, null,
                true, WebPStreamMetadataFormat.NATIVE_FORMAT_NAME, WebPImageMetadataFormat.class.getName(), null, null);
    }

    /**
     * 입력 앞 12바이트가 {@code RIFF} + 크기 4바이트 + {@code WEBP}인지 로컬에서만 검사한다(RFC 9649 §2.4).
     * 네트워크를 쓰지 않고, 스트림 위치를 원래대로 돌려놓는다.
     *
     * @param source 입력. {@link ImageInputStream}이 아니면 false
     * @return WebP 매직 바이트면 true
     * @throws IOException 스트림을 읽다 실패하면
     */
    @Override
    public boolean canDecodeInput(Object source) throws IOException {
        if (!(source instanceof ImageInputStream in)) {
            return false;
        }
        byte[] h = new byte[12];
        in.mark();
        try {
            int n = 0;
            while (n < h.length) {
                int r = in.read(h, n, h.length - n);
                if (r < 0) {
                    return false;
                }
                n += r;
            }
        } finally {
            in.reset();
        }
        return h[0] == 'R' && h[1] == 'I' && h[2] == 'F' && h[3] == 'F'
                && h[8] == 'W' && h[9] == 'E' && h[10] == 'B' && h[11] == 'P';
    }

    /**
     * 새 리더를 만든다. 리더는 스레드 안전하지 않다(ImageIO 규약).
     *
     * @param extension 쓰지 않는다
     * @return 새 {@link ImageReader}
     */
    @Override
    public ImageReader createReaderInstance(Object extension) {
        return new WebPImageReader(this);
    }

    /**
     * 사람이 읽는 설명.
     *
     * @param locale 무시한다(영어만 제공)
     * @return 설명 문자열
     */
    @Override
    public String getDescription(Locale locale) {
        return "WebP image reader (libwebp gRPC sidecar)";
    }
}
