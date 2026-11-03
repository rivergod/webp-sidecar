package net.rivergod.webp.sidecar;

import java.io.IOException;
import java.util.Locale;
import java.util.Objects;
import javax.imageio.ImageReader;
import javax.imageio.spi.ImageReaderSpi;
import javax.imageio.stream.ImageInputStream;

/**
 * WebP {@link ImageReaderSpi}. 디코딩은 libwebp gRPC 사이드카 서버가 한다(ADR-0001). JNI를 쓰지 않는다.
 *
 * <p>서버 엔드포인트와 데드라인은 시스템 프로퍼티 {@code webp.sidecar.endpoint}, {@code webp.sidecar.deadline-ms},
 * {@code webp.sidecar.max-pixels}, {@code webp.sidecar.busy-retries}(또는 같은 이름의 환경변수 {@code WEBP_SIDECAR_*})로
 * 정한다. 기본 엔드포인트는 {@code unix:///run/webp-sidecar/webp.sock}이다.
 *
 * <p>ImageIO 레지스트리가 classpath({@code META-INF/services}) 또는 module path({@code module-info}의
 * {@code provides})에서 찾아 등록한다. 코드로 설정을 주려면 {@link #WebPImageReaderSpi(WebPClientConfig)}로 만들어
 * 수동 등록하거나, {@link #createReaderInstance(Object)}에 {@link WebPClientConfig}를 넘긴다(ADR-0020). 스레드 안전하다.
 */
public final class WebPImageReaderSpi extends ImageReaderSpi {

    static final String VENDOR = "rivergod";
    static final String VERSION = "0.1.0";
    static final String[] NAMES = {"webp", "WEBP", "WebP"};
    static final String[] SUFFIXES = {"webp"};
    static final String[] MIME_TYPES = {"image/webp"};

    private final WebPClientConfig config;

    /** ImageIO 레지스트리가 인자 없이 만든다. 설정은 시스템 프로퍼티 → 환경변수 → 기본값이다. */
    public WebPImageReaderSpi() {
        this(WebPClientConfig.EMPTY);
    }

    /**
     * 등록 단위 설정을 가진 SPI를 만든다(ADR-0020). 비운 항목은 시스템 프로퍼티 → 환경변수 → 기본값으로 채운다.
     * {@code IIORegistry.getDefaultInstance().registerServiceProvider(spi)}로 등록하면 자동 등록된 SPI를 대체한다
     * (레지스트리는 클래스마다 하나만 둔다).
     *
     * @param config 이 SPI가 만드는 리더의 설정
     * @throws NullPointerException {@code config}가 null이면
     */
    public WebPImageReaderSpi(WebPClientConfig config) {
        super(VENDOR, VERSION, NAMES, SUFFIXES, MIME_TYPES, "net.rivergod.webp.sidecar.WebPImageReader",
                new Class<?>[] {ImageInputStream.class}, new String[] {WebPImageWriterSpi.class.getName()},
                false, WebPStreamMetadataFormat.NATIVE_FORMAT_NAME, WebPStreamMetadataFormat.class.getName(), null, null,
                true, WebPStreamMetadataFormat.NATIVE_FORMAT_NAME, WebPImageMetadataFormat.class.getName(), null, null);
        this.config = Objects.requireNonNull(config, "config");
    }

    /** 등록 단위 설정. */
    WebPClientConfig config() {
        return config;
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
     * @param extension {@link WebPClientConfig}면 그 리더의 설정으로 쓰고, 비운 항목은 이 SPI의 설정에서 채운다(ADR-0020).
     *     다른 값이나 null({@code ImageIO.read}가 넘기는 값)이면 이 SPI의 설정만 쓴다
     * @return 새 {@link ImageReader}
     */
    @Override
    public ImageReader createReaderInstance(Object extension) {
        WebPClientConfig readerConfig = extension instanceof WebPClientConfig c ? c.orElse(config) : config;
        return new WebPImageReader(this, readerConfig);
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
