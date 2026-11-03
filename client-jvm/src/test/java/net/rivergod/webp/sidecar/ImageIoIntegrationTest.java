package net.rivergod.webp.sidecar;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Stream;
import javax.imageio.IIOException;
import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageReadParam;
import javax.imageio.ImageReader;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageInputStream;
import javax.imageio.stream.ImageOutputStream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/** 실제 서버 컨테이너로 ImageIO.read/write를 검증한다(Phase 2). 엔드포인트는 시스템 프로퍼티로 바꾼다. */
@Tag("integration")
class ImageIoIntegrationTest {

    private static ServerContainer server;

    @BeforeAll
    static void startServer() throws Exception {
        server = ServerContainer.startNew();
    }

    @AfterAll
    static void stopServer() {
        server.stop();
    }

    @AfterEach
    void clearProperties() {
        System.clearProperty("webp.sidecar.endpoint");
        System.clearProperty("webp.sidecar.max-pixels");
    }

    static Stream<String> transports() {
        return Stream.of("tcp", "uds");
    }

    private static void use(String transport) {
        System.setProperty("webp.sidecar.endpoint", transport.equals("uds") ? server.udsEndpoint() : server.tcpEndpoint());
    }

    @ParameterizedTest
    @MethodSource("transports")
    void read_generatedStillSamples_matchManifestRgba(String transport) throws Exception {
        // given
        use(transport);
        List<JsonObject> samples = new ArrayList<>(TestImages.manifest("generated/still/"));
        samples.removeIf(f -> !f.getAsJsonObject("expected").has("decode"));

        // when / then: 어긋난 파일을 모두 모아 보고한다.
        List<String> failures = new ArrayList<>();
        for (JsonObject f : samples) {
            String path = f.get("path").getAsString();
            JsonObject want = f.getAsJsonObject("expected").getAsJsonObject("decode");
            BufferedImage image = ImageIO.read(TestImages.SAMPLES.resolve(path).toFile());
            if (image == null || image.getWidth() != want.get("width").getAsInt()
                    || !TestImages.rgbaSha256(image).equals(want.get("rgba_sha256").getAsString())) {
                failures.add(path);
            }
        }
        assertTrue(samples.size() >= 20, "samples: " + samples.size());
        assertTrue(failures.isEmpty(), "mismatched: " + failures);
    }

    @ParameterizedTest
    @MethodSource("transports")
    void write_losslessWithAlpha_roundTripsExactly(String transport) throws Exception {
        // given
        use(transport);
        BufferedImage original = TestImages.pattern(257, 123, true);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageWriter writer = ImageIO.getImageWritersByFormatName("webp").next();
        ImageWriteParam param = writer.getDefaultWriteParam();
        param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
        param.setCompressionType("Lossless");
        try (ImageOutputStream ios = ImageIO.createImageOutputStream(out)) {
            writer.setOutput(ios);
            writer.write(null, new IIOImage(original, null, null), param);
        }

        // when
        BufferedImage decoded = ImageIO.read(new ByteArrayInputStream(out.toByteArray()));

        // then
        assertEquals(TestImages.rgbaSha256(original), TestImages.rgbaSha256(decoded));
    }

    @Test
    void write_4kOpaqueImage_exceedsMessageLimitAndRoundTrips() throws Exception {
        // given: 3840x2160 RGBA = 33 MB. 기본 한도(4MB)를 넘어 여러 조각으로 오간다.
        use("uds");
        BufferedImage original = TestImages.pattern(3840, 2160, false);
        ImageWriter writer = ImageIO.getImageWritersByFormatName("webp").next();
        ImageWriteParam param = writer.getDefaultWriteParam();
        param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
        param.setCompressionType("Lossless");
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ImageOutputStream ios = ImageIO.createImageOutputStream(out)) {
            writer.setOutput(ios);
            writer.write(null, new IIOImage(original, null, null), param);
        }

        // when
        BufferedImage decoded = ImageIO.read(new ByteArrayInputStream(out.toByteArray()));

        // then
        assertEquals(BufferedImage.TYPE_INT_RGB, decoded.getType());
        assertEquals(TestImages.rgbaSha256(original), TestImages.rgbaSha256(decoded));
    }

    @Test
    void write_defaultParam_producesLossyWebP() throws Exception {
        // given
        use("tcp");
        ByteArrayOutputStream out = new ByteArrayOutputStream();

        // when
        boolean written = ImageIO.write(TestImages.pattern(64, 48, false), "webp", out);

        // then
        assertTrue(written);
        byte[] b = out.toByteArray();
        assertEquals("VP8 ", new String(b, 12, 4, java.nio.charset.StandardCharsets.ISO_8859_1));
    }

    @Test
    void read_sourceRegionAndSubsampling_matchesManualCrop() throws Exception {
        // given
        use("uds");
        File file = TestImages.SAMPLES.resolve("generated/still/lossless_alpha.webp").toFile();
        BufferedImage full = ImageIO.read(file);
        ImageReader reader = ImageIO.getImageReadersByFormatName("webp").next();
        ImageReadParam param = reader.getDefaultReadParam();
        param.setSourceRegion(new java.awt.Rectangle(3, 2, 20, 10));
        param.setSourceSubsampling(2, 3, 0, 0);
        BufferedImage part;
        try (ImageInputStream in = ImageIO.createImageInputStream(file)) {
            reader.setInput(in);

            // when
            part = reader.read(0, param);
        }

        // then
        assertEquals(10, part.getWidth());
        assertEquals(4, part.getHeight());
        for (int y = 0; y < part.getHeight(); y++) {
            for (int x = 0; x < part.getWidth(); x++) {
                assertEquals(full.getRGB(3 + x * 2, 2 + y * 3), part.getRGB(x, y), "pixel " + x + "," + y);
            }
        }
    }

    @Test
    void read_corruptFile_throwsInvalidDataIioException() {
        // given
        use("tcp");
        File file = TestImages.SAMPLES.resolve("malformed/vp8_bad_start_code.webp").toFile();

        // when
        IIOException e = assertThrows(IIOException.class, () -> ImageIO.read(file));

        // then
        assertTrue(e.getMessage().contains("Invalid or corrupted WebP data"), e.getMessage());
        assertNotNull(e.getCause());
    }

    @Test
    void read_declaredHugeCanvas_throwsLimitIioException() {
        // given: 32바이트 파일이 16383x16383(1 GiB RGBA)을 선언한다.
        use("tcp");
        File file = TestImages.SAMPLES.resolve("malformed/vp8l_bomb_16383x16383.webp").toFile();

        // when
        IIOException e = assertThrows(IIOException.class, () -> ImageIO.read(file));

        // then
        assertTrue(e.getMessage().contains("exceeds server limit"), e.getMessage());
    }

    @Test
    void read_clientMaxPixelsStricter_isEnforced() {
        // given
        use("tcp");
        System.setProperty("webp.sidecar.max-pixels", "100");
        File file = TestImages.SAMPLES.resolve("generated/still/lossless_alpha.webp").toFile();

        // when
        IIOException e = assertThrows(IIOException.class, () -> ImageIO.read(file));

        // then
        assertTrue(e.getMessage().contains("exceeds"), e.getMessage());
    }

    @Test
    void read_noServerAtEndpoint_throwsUnavailableIioException() {
        // given
        System.setProperty("webp.sidecar.endpoint", "unix:///tmp/webp-sidecar-no-such-server.sock");
        File file = TestImages.SAMPLES.resolve("generated/still/lossless_alpha.webp").toFile();

        // when
        IIOException e = assertThrows(IIOException.class, () -> ImageIO.read(file));

        // then
        assertTrue(e.getMessage().startsWith("WebP server unavailable"), e.getMessage());
    }

    @Test
    void serverProcessKilledDuringRequest_jvmSurvivesWithIioException() throws Exception {
        // given: 따로 띄운 서버에 오래 걸리는 인코딩을 보내는 중에 서버 프로세스를 SIGKILL로 죽인다(크래시 모사, ADR-0001).
        ServerContainer victim = ServerContainer.startNew();
        System.setProperty("webp.sidecar.endpoint", victim.udsEndpoint());
        BufferedImage big = TestImages.pattern(4096, 4096, true);
        CompletableFuture<Throwable> result = CompletableFuture.supplyAsync(() -> {
            try {
                ImageWriter writer = ImageIO.getImageWritersByFormatName("webp").next();
                ImageWriteParam param = writer.getDefaultWriteParam();
                param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
                param.setCompressionType("Lossless");
                try (ImageOutputStream ios = ImageIO.createImageOutputStream(new ByteArrayOutputStream())) {
                    writer.setOutput(ios);
                    writer.write(null, new IIOImage(big, null, null), param);
                }
                return null;
            } catch (Throwable t) {
                return t;
            }
        });
        Thread.sleep(700);

        // when
        victim.kill();
        Throwable error = result.get();

        // then: JVM은 살아 있고 IIOException을 받는다. 다른 서버로는 계속 동작한다.
        assertTrue(error instanceof IIOException, String.valueOf(error));
        use("tcp");
        assertNotNull(ImageIO.read(TestImages.SAMPLES.resolve("generated/still/lossless_alpha.webp").toFile()));
        System.out.println("KILLED-SERVER error: " + error.getMessage());
    }

    @Test
    void reader_numImagesAndSize_comeFromGetFeatures() throws Exception {
        // given
        use("uds");
        ImageReader reader = ImageIO.getImageReadersByFormatName("webp").next();
        try (ImageInputStream in = ImageIO.createImageInputStream(
                TestImages.SAMPLES.resolve("generated/still/lossy_3840x2160.webp").toFile())) {
            reader.setInput(in);

            // when
            int[] info = {reader.getNumImages(true), reader.getWidth(0), reader.getHeight(0)};

            // then
            assertArrayEquals(new int[] {1, 3840, 2160}, info);
            assertThrows(IndexOutOfBoundsException.class, () -> reader.getWidth(1));
        }
    }

    @Test
    void writerSpi_formatNames_includeWebp() {
        // when
        String[] names = ImageIO.getWriterFormatNames();

        // then
        assertTrue(List.of(names).contains("webp"), String.join(",", names) + " " + Locale.ROOT);
    }
}
