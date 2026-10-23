package net.rivergod.webp.imageio.bench;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Stream;
import javax.imageio.ImageReader;
import javax.imageio.metadata.IIOMetadata;
import javax.imageio.metadata.IIOMetadataFormatImpl;
import javax.imageio.spi.ImageReaderSpi;
import javax.imageio.stream.MemoryCacheImageInputStream;
import org.junit.jupiter.api.Test;
import org.w3c.dom.NamedNodeMap;
import org.w3c.dom.Node;

/**
 * 표준 메타데이터 트리({@code javax_imageio_1.0})를 TwelveMonkeys imageio-webp와 나란히 비교한다(design/imageio-metadata.md §3,
 * 로드맵 Phase 3에서 Phase 4로 넘긴 확인). 입력은 벤치 세트와 samples/generated의 정지·메타데이터·애니메이션 파일이다.
 *
 * <p>TwelveMonkeys가 읽지 못하는 파일은 비교에서 빼고 표에 남긴다. 설계 문서에 적은 두 차이(VP8X 안 VP8L의 Compression,
 * 애니메이션 프레임의 알파)는 TwelveMonkeys 트리에 그 규칙을 적용한 뒤 비교한다. 그 밖의 차이는 실패다.
 */
class StandardMetadataComparisonTest {

    @Test
    void standardTreeMatchesTwelveMonkeys() throws Exception {
        // Arrange
        BenchTestSupport.requireServer();
        ImageReaderSpi ours = new net.rivergod.webp.imageio.WebPImageReaderSpi();
        ImageReaderSpi tm = new com.twelvemonkeys.imageio.plugins.webp.WebPImageReaderSpi();
        Map<String, byte[]> inputs = new TreeMap<>();
        for (BenchInputs.Content c : BenchInputs.Content.values()) {
            for (BenchInputs.Mode m : BenchInputs.Mode.values()) {
                inputs.put("bench/" + c.dir() + "/S/" + m.name().toLowerCase(java.util.Locale.ROOT),
                        BenchInputs.webp(c, "S", m));
            }
        }
        inputs.put("bench/photo/anim", BenchInputs.animation());
        Path generated = BenchInputs.samplesDir().resolve("generated");
        for (String dir : List.of("still", "meta", "anim")) {
            try (Stream<Path> files = Files.list(generated.resolve(dir))) {
                for (Path p : files.filter(f -> f.toString().endsWith(".webp")).sorted().toList()) {
                    inputs.put("generated/" + dir + "/" + p.getFileName(), Files.readAllBytes(p));
                }
            }
        }
        List<String> mismatches = new ArrayList<>();
        StringBuilder md = new StringBuilder("| 파일 | 결과 |\n|---|---|\n");

        // Act
        for (Map.Entry<String, byte[]> in : inputs.entrySet()) {
            String a;
            String b;
            try {
                a = standardTree(ours, in.getValue());
            } catch (Exception e) {
                mismatches.add(in.getKey() + ": ours failed: " + e);
                md.append("| ").append(in.getKey()).append(" | 이 플러그인 오류: ").append(e).append(" |\n");
                continue;
            }
            try {
                b = standardTree(tm, in.getValue());
            } catch (Exception | LinkageError e) {
                md.append("| ").append(in.getKey()).append(" | 비교 제외(TwelveMonkeys 오류: ")
                        .append(String.valueOf(e).replace('|', '/')).append(") |\n");
                continue;
            }
            String adjusted = documentedDifferences(in.getValue(), b);
            if (a.equals(b)) {
                md.append("| ").append(in.getKey()).append(" | 같음 |\n");
            } else if (a.equals(adjusted)) {
                md.append("| ").append(in.getKey()).append(" | 문서화된 차이만 있음(")
                        .append(String.join(", ", differenceKinds(in.getValue()))).append(") |\n");
            } else {
                mismatches.add(in.getKey() + "\n--- ours\n" + a + "--- twelvemonkeys\n" + b);
                md.append("| ").append(in.getKey()).append(" | **다름** |\n");
            }
        }
        if (!mismatches.isEmpty()) {
            md.append("\n## 차이\n\n```\n").append(String.join("\n", mismatches)).append("```\n");
        }
        BenchTestSupport.writeReport("standard-metadata.md", md.toString());

        // Assert
        assertTrue(mismatches.isEmpty(), () -> String.join("\n", mismatches));
    }

    /**
     * design/imageio-metadata.md §3 "TwelveMonkeys와 값이 다른 경우"를 TwelveMonkeys 트리에 적용한다. VP8X 안 VP8L이면
     * Compression을 VP8L/TRUE로, 애니메이션이면 알파 있음(4채널)으로 바꾼다.
     */
    static String documentedDifferences(byte[] webp, String tmTree) {
        String t = tmTree;
        List<String> kinds = differenceKinds(webp);
        if (kinds.contains("vp8x-lossless")) {
            t = t.replace("CompressionTypeName value=\"VP8\"", "CompressionTypeName value=\"VP8L\"")
                    .replace("Lossless value=\"FALSE\"", "Lossless value=\"TRUE\"");
        }
        if (kinds.contains("animation-alpha")) {
            t = t.replace("NumChannels value=\"3\"", "NumChannels value=\"4\"")
                    .replace("BitsPerSample value=\"8 8 8\"", "BitsPerSample value=\"8 8 8 8\"")
                    .replace("Alpha value=\"none\"", "Alpha value=\"nonpremultiplied\"");
        }
        return t;
    }

    /** 파일 구조로 문서화된 차이가 적용되는지 정한다(RFC 9649 §2.7: VP8X, ANIM, ANMF). */
    static List<String> differenceKinds(byte[] webp) {
        List<String> kinds = new ArrayList<>();
        if (!fourCc(webp, 12).equals("VP8X")) {
            return kinds;
        }
        boolean animated = (webp[20] & 0x02) != 0;
        String firstBitstream = null;
        for (int off = 12; off + 8 <= webp.length && firstBitstream == null; ) {
            String id = fourCc(webp, off);
            int size = le32(webp, off + 4);
            if (id.equals("VP8L") || id.equals("VP8 ")) {
                firstBitstream = id;
            } else if (id.equals("ANMF")) {
                // ANMF 페이로드: 16바이트 프레임 헤더 뒤에 (ALPH) + VP8 / VP8L 하위 청크.
                for (int sub = off + 8 + 16; sub + 8 <= off + 8 + size; sub += 8 + ((le32(webp, sub + 4) + 1) & ~1)) {
                    String sid = fourCc(webp, sub);
                    if (sid.equals("VP8L") || sid.equals("VP8 ")) {
                        firstBitstream = sid;
                        break;
                    }
                }
            }
            off += 8 + ((size + 1) & ~1);
        }
        if ("VP8L".equals(firstBitstream)) {
            kinds.add("vp8x-lossless");
        }
        if (animated) {
            kinds.add("animation-alpha");
        }
        return kinds;
    }

    private static String fourCc(byte[] b, int off) {
        return new String(b, off, 4, java.nio.charset.StandardCharsets.US_ASCII);
    }

    private static int le32(byte[] b, int off) {
        return (b[off] & 0xFF) | (b[off + 1] & 0xFF) << 8 | (b[off + 2] & 0xFF) << 16 | (b[off + 3] & 0xFF) << 24;
    }

    private static String standardTree(ImageReaderSpi spi, byte[] webp) throws Exception {
        ImageReader r = spi.createReaderInstance();
        try (MemoryCacheImageInputStream in = new MemoryCacheImageInputStream(new ByteArrayInputStream(webp))) {
            r.setInput(in, false, false);
            IIOMetadata md = r.getImageMetadata(0);
            StringBuilder sb = new StringBuilder();
            dump(md.getAsTree(IIOMetadataFormatImpl.standardMetadataFormatName), 0, sb);
            return sb.toString();
        } finally {
            r.dispose();
        }
    }

    private static void dump(Node n, int depth, StringBuilder sb) {
        sb.append("  ".repeat(depth)).append(n.getNodeName());
        NamedNodeMap attrs = n.getAttributes();
        if (attrs != null) {
            Map<String, String> sorted = new TreeMap<>();
            for (int i = 0; i < attrs.getLength(); i++) {
                sorted.put(attrs.item(i).getNodeName(), attrs.item(i).getNodeValue());
            }
            sorted.forEach((k, v) -> sb.append(' ').append(k).append("=\"").append(v).append('"'));
        }
        sb.append('\n');
        for (Node c = n.getFirstChild(); c != null; c = c.getNextSibling()) {
            dump(c, depth + 1, sb);
        }
    }
}
