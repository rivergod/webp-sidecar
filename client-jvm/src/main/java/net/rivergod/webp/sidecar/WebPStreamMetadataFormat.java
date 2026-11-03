package net.rivergod.webp.sidecar;

import javax.imageio.ImageTypeSpecifier;
import javax.imageio.metadata.IIOMetadataFormat;
import javax.imageio.metadata.IIOMetadataFormatImpl;

/**
 * WebP 스트림(파일 단위) 메타데이터의 native 포맷 {@value #NATIVE_FORMAT_NAME}(ADR-0014).
 *
 * <pre>
 * &lt;net_rivergod_webp_sidecar_1.0&gt;
 *   &lt;ICCProfile/&gt;   userObject = byte[] (ICCP 청크 페이로드 원본)
 *   &lt;EXIF/&gt;         userObject = byte[] (EXIF 청크 페이로드 원본)
 *   &lt;XMP/&gt;          userObject = byte[] (XMP 청크 페이로드 원본)
 *   &lt;Animation loopCount="0" backgroundColor="0xAARRGGBB"/&gt;
 * &lt;/net_rivergod_webp_sidecar_1.0&gt;
 * </pre>
 *
 * <p>페이로드는 해석하지 않는다. 불변이며 스레드 안전하다.
 */
public final class WebPStreamMetadataFormat extends IIOMetadataFormatImpl {

    /** native 메타데이터 포맷 이름. 스트림과 이미지 메타데이터가 같은 이름을 쓴다. */
    public static final String NATIVE_FORMAT_NAME = "net_rivergod_webp_sidecar_1.0";

    private static final WebPStreamMetadataFormat INSTANCE = new WebPStreamMetadataFormat();

    private WebPStreamMetadataFormat() {
        super(NATIVE_FORMAT_NAME, CHILD_POLICY_SOME);
        for (String chunk : new String[] {"ICCProfile", "EXIF", "XMP"}) {
            addElement(chunk, NATIVE_FORMAT_NAME, CHILD_POLICY_EMPTY);
            addObjectValue(chunk, byte.class, 0, Integer.MAX_VALUE);
        }
        addElement("Animation", NATIVE_FORMAT_NAME, CHILD_POLICY_EMPTY);
        addAttribute("Animation", "loopCount", DATATYPE_INTEGER, false, "0", "0", "65535", true, true);
        addAttribute("Animation", "backgroundColor", DATATYPE_STRING, false, "0xFFFFFFFF");
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
