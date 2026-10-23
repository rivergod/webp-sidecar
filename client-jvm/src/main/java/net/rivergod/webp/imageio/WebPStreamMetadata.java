package net.rivergod.webp.imageio;

import com.google.protobuf.ByteString;
import java.util.Arrays;
import javax.imageio.metadata.IIOInvalidTreeException;
import javax.imageio.metadata.IIOMetadata;
import javax.imageio.metadata.IIOMetadataNode;
import net.rivergod.webp.imageio.proto.v1.AnimationInfo;
import net.rivergod.webp.imageio.proto.v1.Metadata;
import org.w3c.dom.Node;

/**
 * WebP 스트림 메타데이터: ICC/EXIF/XMP 청크 페이로드(원본 바이트)와 애니메이션 전체 정보(ADR-0014).
 * 포맷은 {@link WebPStreamMetadataFormat}. 수정할 수 있다. 스레드 안전하지 않다.
 */
final class WebPStreamMetadata extends IIOMetadata {

    /** libwebp WebPAnimEncoder 기본 배경색(흰색 불투명, src/mux/anim_encode.c). */
    static final int DEFAULT_BACKGROUND_ARGB = 0xFFFFFFFF;

    byte[] iccProfile;
    byte[] exif;
    byte[] xmp;
    /** 반복 횟수. null이면 지정하지 않음(쓸 때 0 = 무한). */
    Integer loopCount;
    /** 배경색 0xAARRGGBB. null이면 지정하지 않음(쓸 때 libwebp 기본값). */
    Integer backgroundArgb;

    WebPStreamMetadata() {
        super(false, WebPStreamMetadataFormat.NATIVE_FORMAT_NAME, WebPStreamMetadataFormat.class.getName(), null, null);
    }

    /** 서버 응답(GetFeatures)에서 만든다. */
    static WebPStreamMetadata from(Metadata metadata, AnimationInfo animation) {
        WebPStreamMetadata m = new WebPStreamMetadata();
        if (metadata != null) {
            m.iccProfile = metadata.hasIccProfile() ? metadata.getIccProfile().toByteArray() : null;
            m.exif = metadata.hasExif() ? metadata.getExif().toByteArray() : null;
            m.xmp = metadata.hasXmp() ? metadata.getXmp().toByteArray() : null;
        }
        if (animation != null) {
            m.loopCount = animation.getLoopCount();
            m.backgroundArgb = animation.getBackgroundColorArgb();
        }
        return m;
    }

    /** 다른 IIOMetadata에서 만든다. 이 클래스면 복사하고, native 포맷을 지원하면 트리로 읽고, 아니면 빈 값. */
    static WebPStreamMetadata of(IIOMetadata source) throws IIOInvalidTreeException {
        WebPStreamMetadata m = new WebPStreamMetadata();
        if (source != null && Arrays.asList(source.getMetadataFormatNames() == null ? new String[0]
                : source.getMetadataFormatNames()).contains(WebPStreamMetadataFormat.NATIVE_FORMAT_NAME)) {
            m.mergeTree(WebPStreamMetadataFormat.NATIVE_FORMAT_NAME,
                    source.getAsTree(WebPStreamMetadataFormat.NATIVE_FORMAT_NAME));
        }
        return m;
    }

    /** 서버에 보낼 메타데이터(설정된 청크만). */
    Metadata toProto() {
        Metadata.Builder b = Metadata.newBuilder();
        if (iccProfile != null) {
            b.setIccProfile(ByteString.copyFrom(iccProfile));
        }
        if (exif != null) {
            b.setExif(ByteString.copyFrom(exif));
        }
        if (xmp != null) {
            b.setXmp(ByteString.copyFrom(xmp));
        }
        return b.build();
    }

    @Override
    public boolean isReadOnly() {
        return false;
    }

    @Override
    public Node getAsTree(String formatName) {
        requireNative(formatName);
        IIOMetadataNode root = new IIOMetadataNode(WebPStreamMetadataFormat.NATIVE_FORMAT_NAME);
        addChunk(root, "ICCProfile", iccProfile);
        addChunk(root, "EXIF", exif);
        addChunk(root, "XMP", xmp);
        if (loopCount != null || backgroundArgb != null) {
            IIOMetadataNode anim = new IIOMetadataNode("Animation");
            anim.setAttribute("loopCount", String.valueOf(loopCount == null ? 0 : loopCount));
            anim.setAttribute("backgroundColor",
                    String.format("0x%08X", backgroundArgb == null ? DEFAULT_BACKGROUND_ARGB : backgroundArgb));
            root.appendChild(anim);
        }
        return root;
    }

    private static void addChunk(IIOMetadataNode root, String name, byte[] payload) {
        if (payload != null) {
            IIOMetadataNode node = new IIOMetadataNode(name);
            node.setUserObject(payload.clone());
            root.appendChild(node);
        }
    }

    @Override
    public void mergeTree(String formatName, Node root) throws IIOInvalidTreeException {
        requireNative(formatName);
        if (!WebPStreamMetadataFormat.NATIVE_FORMAT_NAME.equals(root.getNodeName())) {
            throw new IIOInvalidTreeException("root must be " + WebPStreamMetadataFormat.NATIVE_FORMAT_NAME, root);
        }
        for (Node n = root.getFirstChild(); n != null; n = n.getNextSibling()) {
            switch (n.getNodeName()) {
                case "ICCProfile" -> iccProfile = payload(n);
                case "EXIF" -> exif = payload(n);
                case "XMP" -> xmp = payload(n);
                case "Animation" -> {
                    IIOMetadataNode a = (IIOMetadataNode) n;
                    try {
                        if (!a.getAttribute("loopCount").isEmpty()) {
                            loopCount = Integer.parseInt(a.getAttribute("loopCount"));
                        }
                        String bg = a.getAttribute("backgroundColor");
                        if (!bg.isEmpty()) {
                            backgroundArgb = (int) Long.parseLong(bg.startsWith("0x") || bg.startsWith("0X") ? bg.substring(2) : bg, 16);
                        }
                    } catch (NumberFormatException e) {
                        throw new IIOInvalidTreeException("bad Animation attribute: " + e.getMessage(), e, n);
                    }
                }
                default -> throw new IIOInvalidTreeException("unknown node " + n.getNodeName(), n);
            }
        }
    }

    private static byte[] payload(Node n) throws IIOInvalidTreeException {
        Object value = n instanceof IIOMetadataNode m ? m.getUserObject() : null;
        if (value instanceof byte[] bytes) {
            return bytes.clone();
        }
        throw new IIOInvalidTreeException(n.getNodeName() + " needs a byte[] user object", n);
    }

    private static void requireNative(String formatName) {
        if (!WebPStreamMetadataFormat.NATIVE_FORMAT_NAME.equals(formatName)) {
            throw new IllegalArgumentException("unsupported metadata format: " + formatName);
        }
    }

    @Override
    public void reset() {
        iccProfile = null;
        exif = null;
        xmp = null;
        loopCount = null;
        backgroundArgb = null;
    }
}
