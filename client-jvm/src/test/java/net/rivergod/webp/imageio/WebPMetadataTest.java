package net.rivergod.webp.imageio;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.ArrayList;
import java.util.List;
import javax.imageio.metadata.IIOInvalidTreeException;
import javax.imageio.metadata.IIOMetadataFormatImpl;
import javax.imageio.metadata.IIOMetadataNode;
import org.junit.jupiter.api.Test;
import org.w3c.dom.Node;

class WebPMetadataTest {

    private static final String NATIVE = WebPStreamMetadataFormat.NATIVE_FORMAT_NAME;

    @Test
    void streamMetadata_treeRoundTrip_keepsPayloadsAndAnimation() throws IIOInvalidTreeException {
        // given
        WebPStreamMetadata source = new WebPStreamMetadata();
        source.iccProfile = new byte[] {1, 2, 3};
        source.xmp = "<x/>".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        source.loopCount = 5;
        source.backgroundArgb = 0x80112233;
        WebPStreamMetadata target = new WebPStreamMetadata();

        // when
        target.mergeTree(NATIVE, source.getAsTree(NATIVE));

        // then
        assertArrayEquals(source.iccProfile, target.iccProfile);
        assertArrayEquals(source.xmp, target.xmp);
        assertEquals(null, target.exif);
        assertEquals(5, target.loopCount);
        assertEquals(0x80112233, target.backgroundArgb);
    }

    @Test
    void streamMetadata_unknownNode_isRejected() {
        // given
        IIOMetadataNode root = new IIOMetadataNode(NATIVE);
        root.appendChild(new IIOMetadataNode("Bogus"));

        // when / then
        assertThrows(IIOInvalidTreeException.class, () -> new WebPStreamMetadata().mergeTree(NATIVE, root));
    }

    @Test
    void streamMetadata_nativeFormatClass_isResolvableByImageIo() {
        // when: ImageIO는 포맷 클래스를 리플렉션으로 찾는다(getInstance).
        var format = new WebPStreamMetadata().getMetadataFormat(NATIVE);

        // then
        assertSame(WebPStreamMetadataFormat.getInstance(), format);
    }

    @Test
    void imageMetadata_standardTree_matchesTwelveMonkeysNodeSet() {
        // given
        WebPImageMetadata m = new WebPImageMetadata();
        m.hasAlpha = true;
        m.lossless = true;

        // when
        Node root = m.getAsTree(IIOMetadataFormatImpl.standardMetadataFormatName);

        // then: TwelveMonkeys StandardImageMetadataSupport와 같은 노드(docs/research/0001)
        List<String> children = new ArrayList<>();
        for (Node n = root.getFirstChild(); n != null; n = n.getNextSibling()) {
            children.add(n.getNodeName());
        }
        assertEquals(List.of("Chroma", "Compression", "Data", "Dimension", "Document", "Transparency"), children);
        assertEquals("VP8L", attribute(root, "Compression", "CompressionTypeName"));
        assertEquals("nonpremultiplied", attribute(root, "Transparency", "Alpha"));
        assertEquals("1.0", attribute(root, "Dimension", "PixelAspectRatio"));
    }

    @Test
    void imageMetadata_durationFromNativeTree_isReadForWriting() throws IIOInvalidTreeException {
        // given
        WebPImageMetadata m = new WebPImageMetadata();
        IIOMetadataNode root = new IIOMetadataNode(NATIVE);
        IIOMetadataNode frame = new IIOMetadataNode("Frame");
        frame.setAttribute("durationMs", "250");
        root.appendChild(frame);
        m.mergeTree(NATIVE, root);

        // when
        int duration = WebPImageMetadata.durationOf(m);

        // then
        assertEquals(250, duration);
    }

    @Test
    void imageMetadata_noMetadata_defaultsTo100ms() throws IIOInvalidTreeException {
        // when / then
        assertEquals(100, WebPImageMetadata.durationOf(null));
    }

    @Test
    void imageMetadataFormat_frameElement_isDefined() {
        // when
        var format = WebPImageMetadataFormat.getInstance();

        // then
        assertNotNull(format.getAttributeDefaultValue("Frame", "durationMs"));
        assertEquals("100", format.getAttributeDefaultValue("Frame", "durationMs"));
    }

    private static String attribute(Node root, String parent, String child) {
        for (Node p = root.getFirstChild(); p != null; p = p.getNextSibling()) {
            if (p.getNodeName().equals(parent)) {
                for (Node c = p.getFirstChild(); c != null; c = c.getNextSibling()) {
                    if (c.getNodeName().equals(child)) {
                        Node v = c.getAttributes().getNamedItem("value");
                        return v == null ? null : v.getNodeValue();
                    }
                }
            }
        }
        return null;
    }
}
