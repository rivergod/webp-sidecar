package net.rivergod.webp.imageio;

import java.util.Arrays;
import javax.imageio.metadata.IIOInvalidTreeException;
import javax.imageio.metadata.IIOMetadata;
import javax.imageio.metadata.IIOMetadataFormatImpl;
import javax.imageio.metadata.IIOMetadataNode;
import net.rivergod.webp.imageio.proto.v1.BlendMethod;
import net.rivergod.webp.imageio.proto.v1.Compression;
import net.rivergod.webp.imageio.proto.v1.DisposeMethod;
import net.rivergod.webp.imageio.proto.v1.FrameInfo;
import net.rivergod.webp.imageio.proto.v1.ImageFeatures;
import org.w3c.dom.Node;

/**
 * WebP 이미지(프레임) 메타데이터(ADR-0014). native 포맷은 {@link WebPImageMetadataFormat}, 표준 포맷
 * ({@code javax_imageio_1.0})은 TwelveMonkeys imageio-webp와 같은 노드(Chroma, Compression, Data, Dimension,
 * Document, Transparency)를 낸다. 값이 TwelveMonkeys와 다른 경우는 docs/design/imageio-metadata.md §3에 있다. 수정할 수 있다(쓰기에는 durationMs만 쓴다). 스레드 안전하지 않다.
 */
final class WebPImageMetadata extends IIOMetadata {

    /** 쓰기 기본 표시 시간(ADR-0014). 단위: ms. */
    static final int DEFAULT_DURATION_MS = 100;

    int xOffset;
    int yOffset;
    int width;
    int height;
    int durationMs = DEFAULT_DURATION_MS;
    boolean alphaBlend = true;
    boolean disposeBackground;
    /** 프레임 비트스트림의 알파(native {@code Frame/hasAlpha}). */
    boolean hasAlpha;
    /**
     * 애니메이션 프레임이면 true. {@code read(i)}는 투명으로 초기화한 캔버스에 합성한 ARGB 이미지를 돌려주므로(ADR-0014),
     * 표준 포맷은 프레임 비트스트림과 상관없이 알파가 있는 이미지로 기술한다.
     */
    boolean canvasFrame;
    /** null이면 알 수 없음(쓰기용 기본 메타데이터). */
    Boolean lossless;

    WebPImageMetadata() {
        super(true, WebPStreamMetadataFormat.NATIVE_FORMAT_NAME, WebPImageMetadataFormat.class.getName(), null, null);
    }

    /** 애니메이션 프레임 하나의 ANMF 정보로 만든다. */
    static WebPImageMetadata forFrame(FrameInfo f) {
        WebPImageMetadata m = new WebPImageMetadata();
        m.xOffset = f.getXOffset();
        m.yOffset = f.getYOffset();
        m.width = f.getWidth();
        m.height = f.getHeight();
        m.durationMs = f.getDurationMs();
        m.alphaBlend = f.getBlend() != BlendMethod.BLEND_METHOD_NO_BLEND;
        m.disposeBackground = f.getDispose() == DisposeMethod.DISPOSE_METHOD_BACKGROUND;
        m.hasAlpha = f.getHasAlpha();
        m.canvasFrame = true;
        m.lossless = f.getCompression() == Compression.COMPRESSION_LOSSLESS;
        return m;
    }

    /** 정지 이미지(캔버스 전체를 덮는 프레임 하나)로 만든다. */
    static WebPImageMetadata forStill(ImageFeatures f) {
        WebPImageMetadata m = new WebPImageMetadata();
        m.width = f.getWidth();
        m.height = f.getHeight();
        m.durationMs = 0;
        m.hasAlpha = f.getHasAlpha();
        m.lossless = f.getCompression() == Compression.COMPRESSION_LOSSLESS;
        return m;
    }

    /** 다른 IIOMetadata에서 표시 시간을 꺼낸다. 이 클래스이거나 native 포맷을 지원하면 그 값, 아니면 기본값. */
    static int durationOf(IIOMetadata source) throws IIOInvalidTreeException {
        if (source instanceof WebPImageMetadata m) {
            return m.durationMs;
        }
        if (source != null && source.getMetadataFormatNames() != null
                && Arrays.asList(source.getMetadataFormatNames()).contains(WebPStreamMetadataFormat.NATIVE_FORMAT_NAME)) {
            WebPImageMetadata m = new WebPImageMetadata();
            m.mergeTree(WebPStreamMetadataFormat.NATIVE_FORMAT_NAME, source.getAsTree(WebPStreamMetadataFormat.NATIVE_FORMAT_NAME));
            return m.durationMs;
        }
        return DEFAULT_DURATION_MS;
    }

    @Override
    public boolean isReadOnly() {
        return false;
    }

    @Override
    public Node getAsTree(String formatName) {
        if (IIOMetadataFormatImpl.standardMetadataFormatName.equals(formatName)) {
            return getStandardTree();
        }
        if (!WebPStreamMetadataFormat.NATIVE_FORMAT_NAME.equals(formatName)) {
            throw new IllegalArgumentException("unsupported metadata format: " + formatName);
        }
        IIOMetadataNode root = new IIOMetadataNode(WebPStreamMetadataFormat.NATIVE_FORMAT_NAME);
        IIOMetadataNode frame = new IIOMetadataNode("Frame");
        frame.setAttribute("xOffset", String.valueOf(xOffset));
        frame.setAttribute("yOffset", String.valueOf(yOffset));
        frame.setAttribute("width", String.valueOf(width));
        frame.setAttribute("height", String.valueOf(height));
        frame.setAttribute("durationMs", String.valueOf(durationMs));
        frame.setAttribute("blend", alphaBlend ? "alphaBlend" : "noBlend");
        frame.setAttribute("dispose", disposeBackground ? "background" : "none");
        frame.setAttribute("hasAlpha", hasAlpha ? "TRUE" : "FALSE");
        if (lossless != null) {
            frame.setAttribute("compression", lossless ? "lossless" : "lossy");
        }
        root.appendChild(frame);
        return root;
    }

    @Override
    public void mergeTree(String formatName, Node root) throws IIOInvalidTreeException {
        if (!WebPStreamMetadataFormat.NATIVE_FORMAT_NAME.equals(formatName)) {
            throw new IllegalArgumentException("only the native format can be merged: " + formatName);
        }
        for (Node n = root.getFirstChild(); n != null; n = n.getNextSibling()) {
            if (!"Frame".equals(n.getNodeName())) {
                throw new IIOInvalidTreeException("unknown node " + n.getNodeName(), n);
            }
            IIOMetadataNode f = (IIOMetadataNode) n;
            try {
                if (!f.getAttribute("durationMs").isEmpty()) {
                    durationMs = Integer.parseInt(f.getAttribute("durationMs"));
                }
            } catch (NumberFormatException e) {
                throw new IIOInvalidTreeException("bad durationMs: " + e.getMessage(), e, n);
            }
        }
    }

    @Override
    public void reset() {
        xOffset = 0;
        yOffset = 0;
        width = 0;
        height = 0;
        durationMs = DEFAULT_DURATION_MS;
        alphaBlend = true;
        disposeBackground = false;
        hasAlpha = false;
        canvasFrame = false;
        lossless = null;
    }

    /** 표준 포맷이 기술하는 이미지(= {@code read}가 돌려주는 이미지)에 알파가 있는지. */
    private boolean imageHasAlpha() {
        return hasAlpha || canvasFrame;
    }

    private static IIOMetadataNode node(String name, String attribute, String value) {
        IIOMetadataNode n = new IIOMetadataNode(name);
        n.setAttribute(attribute, value);
        return n;
    }

    @Override
    protected IIOMetadataNode getStandardChromaNode() {
        IIOMetadataNode chroma = new IIOMetadataNode("Chroma");
        chroma.appendChild(node("ColorSpaceType", "name", "RGB"));
        chroma.appendChild(node("NumChannels", "value", imageHasAlpha() ? "4" : "3"));
        chroma.appendChild(node("BlackIsZero", "value", "TRUE"));
        return chroma;
    }

    @Override
    protected IIOMetadataNode getStandardCompressionNode() {
        if (lossless == null) {
            return null;
        }
        IIOMetadataNode compression = new IIOMetadataNode("Compression");
        compression.appendChild(node("CompressionTypeName", "value", lossless ? "VP8L" : "VP8"));
        compression.appendChild(node("Lossless", "value", lossless ? "TRUE" : "FALSE"));
        return compression;
    }

    @Override
    protected IIOMetadataNode getStandardDataNode() {
        IIOMetadataNode data = new IIOMetadataNode("Data");
        data.appendChild(node("PlanarConfiguration", "value", "PixelInterleaved"));
        data.appendChild(node("SampleFormat", "value", "UnsignedIntegral"));
        data.appendChild(node("BitsPerSample", "value", imageHasAlpha() ? "8 8 8 8" : "8 8 8"));
        return data;
    }

    @Override
    protected IIOMetadataNode getStandardDimensionNode() {
        IIOMetadataNode dimension = new IIOMetadataNode("Dimension");
        dimension.appendChild(node("PixelAspectRatio", "value", "1.0"));
        dimension.appendChild(node("ImageOrientation", "value", "Normal"));
        return dimension;
    }

    @Override
    protected IIOMetadataNode getStandardDocumentNode() {
        IIOMetadataNode document = new IIOMetadataNode("Document");
        document.appendChild(node("FormatVersion", "value", "1.0"));
        return document;
    }

    @Override
    protected IIOMetadataNode getStandardTransparencyNode() {
        IIOMetadataNode transparency = new IIOMetadataNode("Transparency");
        transparency.appendChild(node("Alpha", "value", imageHasAlpha() ? "nonpremultiplied" : "none"));
        return transparency;
    }
}
