package net.rivergod.webp.imageio;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.grpc.Status;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.lang.module.Configuration;
import java.lang.module.ModuleFinder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Set;
import javax.imageio.IIOException;
import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageReadParam;
import javax.imageio.ImageReader;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.spi.IIORegistry;
import javax.imageio.spi.ImageReaderSpi;
import javax.imageio.stream.ImageOutputStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * ADR-0020: 코드로 준 설정, 읽기·쓰기 파라미터, 혼잡 재시도, 수동 등록. 가짜 서버({@link FakeWebPServer})를 쓰므로
 * Docker가 필요 없다.
 */
class ConfigInjectionTest {

    private FakeWebPServer server;
    private byte[] still;

    @BeforeEach
    void start() throws Exception {
        server = FakeWebPServer.start();
        still = Files.readAllBytes(TestImages.SAMPLES.resolve("generated/still/lossy_17x9.webp"));
    }

    @AfterEach
    void stop() throws Exception {
        server.close();
    }

    private WebPClientConfig.Builder config() {
        return WebPClientConfig.builder().endpoint(server.endpoint());
    }

    private BufferedImage read(WebPClientConfig config, ImageReadParam param) throws Exception {
        ImageReader reader = new WebPImageReaderSpi().createReaderInstance(config);
        try {
            reader.setInput(ImageIO.createImageInputStream(new ByteArrayInputStream(still)));
            return reader.read(0, param);
        } finally {
            reader.dispose();
        }
    }

    private void write(WebPClientConfig config, ImageWriteParam param) throws Exception {
        ImageWriter writer = new WebPImageWriterSpi().createWriterInstance(config);
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ImageOutputStream out = ImageIO.createImageOutputStream(bytes)) {
            writer.setOutput(out);
            writer.write(null, new IIOImage(TestImages.pattern(8, 4, false), null, null), param);
        } finally {
            writer.dispose();
        }
    }

    @Test
    void read_busyThenFree_succeedsAfterRetries() throws Exception {
        // given
        server.rejectFirst(2);

        // when
        BufferedImage image = read(config().busyRetries(2).build(), null);

        // then
        assertEquals(1, image.getWidth());
        assertEquals(3, server.decodeCalls.get());
    }

    @Test
    void read_busyMoreOftenThanRetries_failsWithServerBusy() {
        // given
        server.rejectFirst(5);

        // when
        IIOException e = assertThrows(IIOException.class, () -> read(config().busyRetries(1).build(), null));

        // then
        assertTrue(e.getMessage().contains("WebP server busy"), e.getMessage());
        assertEquals(2, server.decodeCalls.get());
    }

    @Test
    void read_retriesNotConfigured_failsOnFirstBusy() {
        // given
        server.rejectFirst(1);

        // when
        assertThrows(IIOException.class, () -> read(config().build(), null));

        // then
        assertEquals(1, server.decodeCalls.get());
    }

    @Test
    void read_alwaysBusy_stopsWithinDeadline() {
        // given: 데드라인 250 ms. 물러섬 100 ms 뒤 남은 시간이 다음 물러섬(200 ms)보다 짧다
        server.rejectFirst(Integer.MAX_VALUE);
        long started = System.nanoTime();

        // when
        assertThrows(IIOException.class,
                () -> read(config().busyRetries(WebPClientConfig.MAX_BUSY_RETRIES).deadlineMs(250).build(), null));

        // then
        long elapsedMs = (System.nanoTime() - started) / 1_000_000;
        assertTrue(elapsedMs < 1_000, "took " + elapsedMs + " ms");
        assertTrue(server.decodeCalls.get() <= 3, "calls " + server.decodeCalls.get());
    }

    @Test
    void read_unavailableForOtherReasons_isNotRetried() {
        // given
        server.rejectFirst(1).rejectWith(Status.UNAVAILABLE.withDescription("server is shutting down"));

        // when
        assertThrows(IIOException.class, () -> read(config().busyRetries(3).build(), null));

        // then
        assertEquals(1, server.decodeCalls.get());
    }

    @Test
    void read_webPReadParam_sendsItsDeadlineAndMaxPixels() throws Exception {
        // given
        WebPReadParam param = new WebPReadParam();
        param.setDeadlineMs(5_000);
        param.setMaxPixels(1_000);

        // when
        read(config().build(), param);

        // then: 서버가 본 남은 데드라인은 5초 이하다(리더 기본값 30초가 아니다)
        long seen = server.lastDeadlineMs.get();
        assertTrue(seen > 0 && seen <= 5_000, "deadline seen by server: " + seen + " ms");
        assertEquals(1_000, server.lastDecode.get().getMaxPixels());
    }

    @Test
    void read_readerConfigDeadline_isUsedWithoutParam() throws Exception {
        // when
        read(config().deadlineMs(7_000).build(), null);

        // then
        long seen = server.lastDeadlineMs.get();
        assertTrue(seen > 5_000 && seen <= 7_000, "deadline seen by server: " + seen + " ms");
    }

    @Test
    void write_busyThenFree_resendsAllPixels() throws Exception {
        // given
        server.rejectFirst(1);

        // when
        write(config().busyRetries(1).build(), null);

        // then: 두 번째 시도가 8×4 RGBA 전체를 보냈다
        assertEquals(2, server.encodeCalls.get());
        assertEquals(8 * 4 * 4, server.lastPixelBytes.get());
    }

    @Test
    void write_webPWriteParamMethod_reachesServer() throws Exception {
        // given
        WebPWriteParam param = new WebPWriteParam(null);
        param.setMethod(6);

        // when
        write(config().build(), param);

        // then
        assertEquals(6, server.lastEncode.get().getParams().getMethod());
    }

    @Test
    void manualRegistration_configuredSpi_replacesAutoRegisteredOneForImageIoRead() throws Exception {
        // given
        IIORegistry registry = IIORegistry.getDefaultInstance();
        WebPImageReaderSpi configured = new WebPImageReaderSpi(config().build());
        try {
            registry.registerServiceProvider(configured);

            // when
            BufferedImage image = ImageIO.read(new ByteArrayInputStream(still));

            // then: 같은 클래스는 하나만 남고, ImageIO.read가 그 설정의 엔드포인트를 쓴다
            assertSame(configured, registry.getServiceProviderByClass(WebPImageReaderSpi.class));
            assertEquals(1, image.getWidth());
            assertEquals(1, server.decodeCalls.get());
        } finally {
            registry.registerServiceProvider(new WebPImageReaderSpi());
        }
    }

    @Test
    void modulePath_publicConfigConstructor_readerUsesItsEndpoint() throws Exception {
        // given: 빌드한 jar를 module path 레이어로 올리고, 공개 API만 리플렉션으로 부른다
        Path[] paths = Arrays.stream(System.getProperty("webp.test.modulePath").split(File.pathSeparator))
                .map(Path::of).toArray(Path[]::new);
        ModuleLayer parent = ModuleLayer.boot();
        Configuration cf = parent.configuration()
                .resolve(ModuleFinder.of(paths), ModuleFinder.of(), Set.of("net.rivergod.webp.imageio"));
        ModuleLayer layer = parent.defineModulesWithOneLoader(cf, ClassLoader.getPlatformClassLoader());
        ClassLoader loader = layer.findLoader("net.rivergod.webp.imageio");
        Class<?> configClass = loader.loadClass("net.rivergod.webp.imageio.WebPClientConfig");
        Object builder = configClass.getMethod("builder").invoke(null);
        builder.getClass().getMethod("endpoint", String.class).invoke(builder, server.endpoint());
        Object config = builder.getClass().getMethod("build").invoke(builder);
        ImageReaderSpi spi = (ImageReaderSpi) loader.loadClass("net.rivergod.webp.imageio.WebPImageReaderSpi")
                .getConstructor(configClass).newInstance(config);
        ImageReader reader = spi.createReaderInstance();
        reader.setInput(ImageIO.createImageInputStream(new ByteArrayInputStream(still)));

        // when
        BufferedImage image = reader.read(0);

        // then
        assertEquals("net.rivergod.webp.imageio", spi.getClass().getModule().getName());
        assertEquals(1, image.getWidth());
        assertEquals(1, server.decodeCalls.get());
        reader.dispose();
    }
}
