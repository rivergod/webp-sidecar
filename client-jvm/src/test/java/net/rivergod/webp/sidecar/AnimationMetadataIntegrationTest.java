package net.rivergod.webp.sidecar;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.metadata.IIOMetadata;
import javax.imageio.stream.ImageInputStream;
import javax.imageio.stream.ImageOutputStream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** Phase 3: 애니메이션 읽기·쓰기와 ICC/EXIF/XMP를 실제 서버로 검증한다(ADR-0014). */
@Tag("integration")
class AnimationMetadataIntegrationTest {

    private static ServerContainer server;

    @BeforeAll
    static void startServer() throws Exception {
        server = ServerContainer.startNew();
        System.setProperty("webp.sidecar.endpoint", server.udsEndpoint());
    }

    @AfterAll
    static void stopServer() {
        System.clearProperty("webp.sidecar.endpoint");
        server.stop();
    }

    private static ImageReader readerFor(File file) throws Exception {
        ImageReader reader = ImageIO.getImageReadersByFormatName("webp").next();
        ImageInputStream in = ImageIO.createImageInputStream(file);
        reader.setInput(in);
        return reader;
    }

    @Test
    void sequentialRead_generatedAnimations_matchManifestFramesWithOneStreamEach() throws Exception {
        // given: 생성 애니메이션 전부(프레임별 합성 캔버스 해시, 표시 시간)
        List<JsonObject> samples = new ArrayList<>();
        for (String prefix : new String[] {"generated/anim/", "generated/meta/"}) {
            for (JsonObject f : TestImages.manifest(prefix)) {
                if (f.getAsJsonObject("expected").has("animation") && f.get("path").getAsString().endsWith(".webp")) {
                    samples.add(f);
                }
            }
        }
        List<String> failures = new ArrayList<>();

        // when / then: read(0..n-1)을 차례로 부르면 DecodeAnimation 스트림은 파일마다 한 번만 열린다.
        for (JsonObject f : samples) {
            String path = f.get("path").getAsString();
            JsonArray frames = f.getAsJsonObject("expected").getAsJsonObject("animation").getAsJsonArray("frames");
            long opened = AnimationStream.OPENED.get();
            ImageReader reader = readerFor(TestImages.SAMPLES.resolve(path).toFile());
            try {
                if (reader.getNumImages(true) != frames.size()) {
                    failures.add(path + ": numImages " + reader.getNumImages(true));
                    continue;
                }
                for (int i = 0; i < frames.size(); i++) {
                    JsonObject want = frames.get(i).getAsJsonObject();
                    BufferedImage image = reader.read(i);
                    WebPImageMetadata meta = (WebPImageMetadata) reader.getImageMetadata(i);
                    if (!TestImages.rgbaSha256(image).equals(want.get("rgba_sha256").getAsString())
                            || meta.durationMs != want.get("duration_ms").getAsInt()) {
                        failures.add(path + ": frame " + i);
                    }
                }
            } finally {
                reader.dispose();
            }
            if (AnimationStream.OPENED.get() - opened != 1) {
                failures.add(path + ": opened " + (AnimationStream.OPENED.get() - opened) + " streams");
            }
        }
        assertTrue(samples.size() >= 10, "samples: " + samples.size());
        assertTrue(failures.isEmpty(), "mismatched: " + failures);
    }

    @Test
    void randomAccessRead_animation_matchesManifestFrames() throws Exception {
        // given
        JsonObject f = TestImages.manifest("generated/anim/offsets.webp").get(0);
        JsonArray frames = f.getAsJsonObject("expected").getAsJsonObject("animation").getAsJsonArray("frames");
        ImageReader reader = readerFor(TestImages.SAMPLES.resolve(f.get("path").getAsString()).toFile());
        try {
            // when / then: 뒤에서 앞으로 읽어도(Decode(frame_index)) 같은 합성 결과다.
            for (int i = frames.size() - 1; i >= 0; i--) {
                String want = frames.get(i).getAsJsonObject().get("rgba_sha256").getAsString();
                assertEquals(want, TestImages.rgbaSha256(reader.read(i)), "frame " + i);
            }
        } finally {
            reader.dispose();
        }
    }

    @Test
    void streamMetadata_metaSamples_matchManifestChunkHashes() throws Exception {
        // given
        List<String> failures = new ArrayList<>();
        int checked = 0;
        for (JsonObject f : TestImages.manifest("generated/meta/")) {
            JsonObject expected = f.getAsJsonObject("expected");
            if (!expected.has("metadata")) {
                continue;
            }
            String path = f.get("path").getAsString();
            ImageReader reader = readerFor(TestImages.SAMPLES.resolve(path).toFile());
            try {
                // when
                WebPStreamMetadata meta = (WebPStreamMetadata) reader.getStreamMetadata();

                // then
                JsonObject want = expected.getAsJsonObject("metadata");
                for (var e : new Object[][] {{"icc", meta.iccProfile}, {"exif", meta.exif}, {"xmp", meta.xmp}}) {
                    String key = (String) e[0];
                    String got = e[1] == null ? null : sha256((byte[]) e[1]);
                    String exp = want.has(key) && want.get(key).isJsonObject()
                            ? want.getAsJsonObject(key).get("sha256").getAsString() : null;
                    if (!java.util.Objects.equals(got, exp)) {
                        failures.add(path + " " + key);
                    }
                }
                checked++;
            } finally {
                reader.dispose();
            }
        }
        assertTrue(checked > 0);
        assertTrue(failures.isEmpty(), "mismatched: " + failures);
    }

    @Test
    void writeSequence_losslessFrames_roundTripPixelsDurationsLoopAndBackground() throws Exception {
        // given
        BufferedImage[] frames = {TestImages.pattern(40, 30, true), TestImages.pattern(40, 30, false), TestImages.pattern(40, 30, true)};
        frames[2].setRGB(5, 5, 0x12345678);
        int[] durations = {80, 160, 240};
        ImageWriter writer = ImageIO.getImageWritersByFormatName("webp").next();
        ImageWriteParam param = writer.getDefaultWriteParam();
        param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
        param.setCompressionType("Lossless");
        WebPStreamMetadata stream = (WebPStreamMetadata) writer.getDefaultStreamMetadata(param);
        stream.loopCount = 4;
        stream.backgroundArgb = 0xFF00FF00;
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ImageOutputStream ios = ImageIO.createImageOutputStream(out)) {
            writer.setOutput(ios);
            writer.prepareWriteSequence(stream);
            for (int i = 0; i < frames.length; i++) {
                WebPImageMetadata meta = (WebPImageMetadata) writer.getDefaultImageMetadata(null, param);
                meta.durationMs = durations[i];
                writer.writeToSequence(new IIOImage(frames[i], null, meta), param);
            }

            // when
            writer.endWriteSequence();
        }

        // then
        ImageReader reader = ImageIO.getImageReadersByFormatName("webp").next();
        reader.setInput(ImageIO.createImageInputStream(new ByteArrayInputStream(out.toByteArray())));
        try {
            assertEquals(3, reader.getNumImages(true));
            WebPStreamMetadata read = (WebPStreamMetadata) reader.getStreamMetadata();
            assertEquals(4, read.loopCount);
            assertEquals(0xFF00FF00, read.backgroundArgb);
            for (int i = 0; i < frames.length; i++) {
                assertEquals(TestImages.rgbaSha256(frames[i]), TestImages.rgbaSha256(reader.read(i)), "frame " + i);
                assertEquals(durations[i], ((WebPImageMetadata) reader.getImageMetadata(i)).durationMs);
            }
        } finally {
            reader.dispose();
        }
    }

    @Test
    void writeStill_withStreamMetadata_roundTripsIccExifXmp() throws Exception {
        // given
        ImageWriter writer = ImageIO.getImageWritersByFormatName("webp").next();
        WebPStreamMetadata stream = (WebPStreamMetadata) writer.getDefaultStreamMetadata(null);
        stream.iccProfile = java.nio.file.Files.readAllBytes(TestImages.SAMPLES.resolve("third_party/compact-icc/sRGB-v4.icc"));
        stream.exif = "Exif\0\0II*\0".getBytes(java.nio.charset.StandardCharsets.ISO_8859_1);
        stream.xmp = "<x:xmpmeta xmlns:x=\"adobe:ns:meta/\"/>".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ImageOutputStream ios = ImageIO.createImageOutputStream(out)) {
            writer.setOutput(ios);

            // when
            writer.write(stream, new IIOImage(TestImages.pattern(20, 10, false), null, null), null);
        }

        // then
        ImageReader reader = ImageIO.getImageReadersByFormatName("webp").next();
        reader.setInput(ImageIO.createImageInputStream(new ByteArrayInputStream(out.toByteArray())));
        try {
            IIOMetadata read = reader.getStreamMetadata();
            WebPStreamMetadata m = (WebPStreamMetadata) read;
            assertArrayEquals(stream.iccProfile, m.iccProfile);
            assertArrayEquals(stream.exif, m.exif);
            assertArrayEquals(stream.xmp, m.xmp);
        } finally {
            reader.dispose();
        }
    }

    @Test
    void imageIoRead_animatedFile_returnsFirstCompositedFrame() throws Exception {
        // given
        JsonObject f = TestImages.manifest("generated/anim/loop_1.webp").get(0);
        String want = f.getAsJsonObject("expected").getAsJsonObject("animation").getAsJsonArray("frames")
                .get(0).getAsJsonObject().get("rgba_sha256").getAsString();

        // when
        BufferedImage image = ImageIO.read(TestImages.SAMPLES.resolve(f.get("path").getAsString()).toFile());

        // then
        assertEquals(want, TestImages.rgbaSha256(image));
    }

    private static String sha256(byte[] b) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(b));
    }
}
