package net.rivergod.webp.imageio;

import java.util.Locale;
import javax.imageio.ImageWriteParam;
import net.rivergod.webp.imageio.proto.v1.EncodeParams;

/**
 * WebP 쓰기 파라미터(ADR-0013). 압축 타입은 {@value #LOSSY}, {@value #LOSSLESS} 두 가지이고 품질 0.0–1.0을
 * libwebp quality 0–100으로 바꾼다. 그 밖의 libwebp 옵션은 노출하지 않는다.
 */
final class WebPWriteParam extends ImageWriteParam {

    static final String LOSSY = "Lossy";
    static final String LOSSLESS = "Lossless";

    WebPWriteParam(Locale locale) {
        super(locale);
        canWriteCompressed = true;
        compressionTypes = new String[] {LOSSY, LOSSLESS};
        compressionType = LOSSY;
        // libwebp 기본값(WebPConfigInit: quality 75)과 맞춘다.
        compressionQuality = 0.75f;
    }

    @Override
    public boolean isCompressionLossless() {
        super.isCompressionLossless();
        return LOSSLESS.equals(getCompressionType());
    }

    /**
     * 표준 파라미터를 서버 인코더 설정으로 바꾼다.
     *
     * <ul>
     *   <li>null, MODE_DEFAULT, MODE_COPY_FROM_METADATA: 서버 기본값(lossy, libwebp 기본 품질)
     *   <li>MODE_EXPLICIT: 압축 타입과 품질(× 100)
     *   <li>MODE_DISABLED: WebP는 항상 압축하므로 손실이 없는 lossless로 쓴다
     * </ul>
     */
    static EncodeParams toEncodeParams(ImageWriteParam param) {
        EncodeParams.Builder p = EncodeParams.newBuilder();
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
