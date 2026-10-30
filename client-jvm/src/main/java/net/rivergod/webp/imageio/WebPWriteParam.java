package net.rivergod.webp.imageio;

import java.util.Locale;
import javax.imageio.ImageWriteParam;
import net.rivergod.webp.imageio.proto.v1.EncodeParams;

/**
 * WebP 쓰기 파라미터(ADR-0013, ADR-0020). writer의 {@code getDefaultWriteParam()}이 이 타입을 돌려준다.
 * 스레드 안전하지 않다.
 *
 * <ul>
 *   <li>압축 타입은 {@value #LOSSY}, {@value #LOSSLESS} 두 가지다. 품질 0.0–1.0을 libwebp quality 0–100으로 바꾼다
 *       ({@link #setCompressionMode}가 {@code MODE_EXPLICIT}일 때).
 *   <li>{@link #setMethod}: libwebp 인코더 method 0(빠름)–6(느림, 더 작음). 압축 모드와 상관없이 적용한다.
 * </ul>
 *
 * <pre>{@code
 * WebPWriteParam param = (WebPWriteParam) writer.getDefaultWriteParam();
 * param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
 * param.setCompressionType(WebPWriteParam.LOSSLESS);
 * param.setMethod(6);
 * }</pre>
 */
public final class WebPWriteParam extends ImageWriteParam {

    /** 손실 압축 타입 이름. */
    public static final String LOSSY = "Lossy";

    /** 무손실 압축 타입 이름. */
    public static final String LOSSLESS = "Lossless";

    /** {@link #getMethod()}가 이 값이면 서버(libwebp) 기본값을 쓴다. */
    public static final int DEFAULT_METHOD = -1;

    private int method = DEFAULT_METHOD;

    /**
     * 기본 파라미터를 만든다: 압축 타입 {@value #LOSSY}, 품질 0.75(libwebp 기본값 75), method는 서버 기본값.
     *
     * @param locale 압축 타입 설명에 쓸 로캘. null이면 기본 로캘
     */
    public WebPWriteParam(Locale locale) {
        super(locale);
        canWriteCompressed = true;
        compressionTypes = new String[] {LOSSY, LOSSLESS};
        compressionType = LOSSY;
        // libwebp 기본값(WebPConfigInit: quality 75)과 맞춘다.
        compressionQuality = 0.75f;
    }

    /**
     * 압축 타입이 {@value #LOSSLESS}인지.
     *
     * @return 무손실이면 true
     * @throws IllegalStateException 압축 모드가 {@code MODE_EXPLICIT}가 아니거나 압축 타입을 정하지 않았으면
     */
    @Override
    public boolean isCompressionLossless() {
        super.isCompressionLossless();
        return LOSSLESS.equals(getCompressionType());
    }

    /**
     * libwebp 인코더 method를 정한다. 클수록 느리고 결과가 작다.
     *
     * @param method 0–6, 또는 {@link #DEFAULT_METHOD}(서버 기본값)
     * @throws IllegalArgumentException 범위를 벗어나면
     */
    public void setMethod(int method) {
        if (method < DEFAULT_METHOD || method > 6) {
            throw new IllegalArgumentException("method must be 0..6 or DEFAULT_METHOD (-1), got " + method);
        }
        this.method = method;
    }

    /**
     * libwebp 인코더 method.
     *
     * @return 0–6, 또는 정하지 않았으면 {@link #DEFAULT_METHOD}
     */
    public int getMethod() {
        return method;
    }

    /**
     * 표준 파라미터를 서버 인코더 설정으로 바꾼다.
     *
     * <ul>
     *   <li>null, MODE_DEFAULT, MODE_COPY_FROM_METADATA: 서버 기본값(lossy, libwebp 기본 품질)
     *   <li>MODE_EXPLICIT: 압축 타입과 품질(× 100)
     *   <li>MODE_DISABLED: WebP는 항상 압축하므로 손실이 없는 lossless로 쓴다
     *   <li>{@link WebPWriteParam}의 method는 압축 모드와 상관없이 넘긴다(ADR-0020)
     * </ul>
     */
    static EncodeParams toEncodeParams(ImageWriteParam param) {
        EncodeParams.Builder p = EncodeParams.newBuilder();
        if (param instanceof WebPWriteParam w && w.getMethod() != DEFAULT_METHOD) {
            p.setMethod(w.getMethod());
        }
        if (param == null || !param.canWriteCompressed()) {
            return p.build();
        }
        switch (param.getCompressionMode()) {
            case MODE_EXPLICIT -> {
                p.setLossless(LOSSLESS.equals(param.getCompressionType()));
                p.setQuality(param.getCompressionQuality() * 100f);
            }
            case MODE_DISABLED -> p.setLossless(true);
            default -> {
                // 서버 기본값
            }
        }
        return p.build();
    }
}
