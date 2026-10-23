package net.rivergod.webp.imageio;

import javax.imageio.ImageTypeSpecifier;
import javax.imageio.metadata.IIOMetadataFormat;
import javax.imageio.metadata.IIOMetadataFormatImpl;

/**
 * WebP 이미지(프레임 단위) 메타데이터의 native 포맷 {@value WebPStreamMetadataFormat#NATIVE_FORMAT_NAME}(ADR-0014).
 * RFC 9649 §2.7.1.1의 ANMF 정보를 담는다. 정지 이미지는 캔버스 전체를 덮는 프레임 하나다.
 *
 * <pre>
 * &lt;net_rivergod_webp_imageio_1.0&gt;
 *   &lt;Frame xOffset="0" yOffset="0" width="…" height="…" durationMs="100"
 *          blend="alphaBlend|noBlend" dispose="none|background" hasAlpha="TRUE|FALSE" compression="lossy|lossless"/&gt;
 * &lt;/net_rivergod_webp_imageio_1.0&gt;
 * </pre>
 *
 * <p>쓸 때는 {@code durationMs}만 쓴다(없으면 100 ms). 불변이며 스레드 안전하다.
 */
public final class WebPImageMetadataFormat extends IIOMetadataFormatImpl {

    private static final WebPImageMetadataFormat INSTANCE = new WebPImageMetadataFormat();

    private WebPImageMetadataFormat() {
        super(WebPStreamMetadataFormat.NATIVE_FORMAT_NAME, CHILD_POLICY_SOME);
        addElement("Frame", WebPStreamMetadataFormat.NATIVE_FORMAT_NAME, CHILD_POLICY_EMPTY);
        for (String a : new String[] {"xOffset", "yOffset", "width", "height"}) {
            addAttribute("Frame", a, DATATYPE_INTEGER, false, null, "0", null, true, false);
        }
        addAttribute("Frame", "durationMs", DATATYPE_INTEGER, false, "100", "0", "16777215", true, true);
        addAttribute("Frame", "blend", DATATYPE_STRING, false, "alphaBlend", java.util.List.of("alphaBlend", "noBlend"));
        addAttribute("Frame", "dispose", DATATYPE_STRING, false, "none", java.util.List.of("none", "background"));
        addBooleanAttribute("Frame", "hasAlpha", false, false);
        addAttribute("Frame", "compression", DATATYPE_STRING, false, null, java.util.List.of("lossy", "lossless"));
    }

    /**
     * 모든 노드가 어떤 이미지 타입에서도 나타날 수 있다.
     *
     * @param elementName 요소 이름
     * @param imageType 이미지 타입
     * @return 항상 true
     */
    @Override
    public boolean canNodeAppear(String elementName, ImageTypeSpecifier imageType) {
        return true;
    }

    /**
     * 공유 인스턴스. ImageIO가 리플렉션으로 부른다.
     *
     * @return 포맷 정의
     */
    public static IIOMetadataFormat getInstance() {
        return INSTANCE;
    }
}
