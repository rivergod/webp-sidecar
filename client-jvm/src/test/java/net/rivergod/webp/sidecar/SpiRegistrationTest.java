package net.rivergod.webp.sidecar;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.lang.module.Configuration;
import java.lang.module.ModuleFinder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Set;
import java.util.stream.StreamSupport;
import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.spi.ImageReaderSpi;
import javax.imageio.spi.ImageWriterSpi;
import org.junit.jupiter.api.Test;

class SpiRegistrationTest {

    @Test
    void imageIo_classpath_findsWebPReaderAndWriter() {
        // when
        boolean reader = ImageIO.getImageReadersByFormatName("webp").next() instanceof WebPImageReader;
        boolean writer = ImageIO.getImageWritersByMIMEType("image/webp").next() instanceof WebPImageWriter;

        // then
        assertTrue(reader && writer);
    }

    @Test
    void serviceLoader_modulePath_findsProvidersFromModuleInfo() {
        // given: 빌드한 jar와 런타임 의존성으로 module path 레이어를 만든다(Gradle이 경로를 넘긴다).
        Path[] paths = Arrays.stream(System.getProperty("webp.test.modulePath").split(File.pathSeparator))
                .map(Path::of).toArray(Path[]::new);
        ModuleFinder finder = ModuleFinder.of(paths);
        ModuleLayer parent = ModuleLayer.boot();
        Configuration cf = parent.configuration().resolve(finder, ModuleFinder.of(), Set.of("net.rivergod.webp.sidecar"));
        ModuleLayer layer = parent.defineModulesWithOneLoader(cf, ClassLoader.getPlatformClassLoader());

        // when
        boolean reader = StreamSupport.stream(java.util.ServiceLoader.load(layer, ImageReaderSpi.class).spliterator(), false)
                .anyMatch(s -> s.getClass().getName().equals("net.rivergod.webp.sidecar.WebPImageReaderSpi")
                        && s.getClass().getModule().getName().equals("net.rivergod.webp.sidecar"));
        boolean writer = StreamSupport.stream(java.util.ServiceLoader.load(layer, ImageWriterSpi.class).spliterator(), false)
                .anyMatch(s -> s.getClass().getName().equals("net.rivergod.webp.sidecar.WebPImageWriterSpi"));

        // then
        assertTrue(reader && writer);
    }

    /** 빌드한 jar와 런타임 의존성으로 module path 레이어를 만들고, 그 레이어의 WebP 리더 SPI를 찾는다. */
    private static ImageReaderSpi moduleLayerReaderSpi() {
        Path[] paths = Arrays.stream(System.getProperty("webp.test.modulePath").split(File.pathSeparator))
                .map(Path::of).toArray(Path[]::new);
        ModuleLayer parent = ModuleLayer.boot();
        Configuration cf = parent.configuration()
                .resolve(ModuleFinder.of(paths), ModuleFinder.of(), Set.of("net.rivergod.webp.sidecar"));
        ModuleLayer layer = parent.defineModulesWithOneLoader(cf, ClassLoader.getPlatformClassLoader());
        return StreamSupport.stream(java.util.ServiceLoader.load(layer, ImageReaderSpi.class).spliterator(), false)
                .filter(s -> s.getClass().getName().equals("net.rivergod.webp.sidecar.WebPImageReaderSpi"))
                .findFirst().orElseThrow();
    }

    private static BufferedImage readOnce(ImageReaderSpi spi) throws Exception {
        byte[] still = Files.readAllBytes(TestImages.SAMPLES.resolve("generated/still/lossy_17x9.webp"));
        ImageReader reader = spi.createReaderInstance();
        try {
            reader.setInput(ImageIO.createImageInputStream(new ByteArrayInputStream(still)));
            return reader.read(0);
        } finally {
            reader.dispose();
        }
    }

    @Test
    void modulePath_readerFromModuleLayer_completesAnRpc() throws Exception {
        // given: module path 레이어의 SPI로 만든 리더가 실제로 서버를 부른다(netty 모듈 해석 확인). 엔드포인트는 가짜 서버.
        ImageReaderSpi spi = moduleLayerReaderSpi();
        try (FakeWebPServer server = FakeWebPServer.start()) {
            System.setProperty("webp.sidecar.endpoint", server.endpoint());
            try {
                // when
                BufferedImage image = readOnce(spi);

                // then
                assertEquals(1, image.getWidth());
                assertEquals(1, server.decodeCalls.get());
            } finally {
                System.clearProperty("webp.sidecar.endpoint");
            }
        }
    }

    /**
     * TCP 엔드포인트를 HTTP CONNECT 프록시를 거쳐 부른다. grpc-java는 {@code java.net.ProxySelector}로 프록시를 찾는다.
     * 루프백도 거치도록 {@code http.nonProxyHosts}를 비운다. 끝나면 프로퍼티를 지운다.
     */
    private static void readThroughProxy(ImageReaderSpi spi) throws Exception {
        try (FakeWebPServer server = FakeWebPServer.start(); ConnectProxy proxy = ConnectProxy.start()) {
            System.setProperty("webp.sidecar.endpoint", server.endpoint());
            System.setProperty("https.proxyHost", "127.0.0.1");
            System.setProperty("https.proxyPort", Integer.toString(proxy.port()));
            System.setProperty("http.nonProxyHosts", "");
            try {
                // when
                BufferedImage image = readOnce(spi);

                // then: 프록시를 한 번 거쳐 서버까지 갔다
                assertEquals(1, image.getWidth());
                assertEquals(1, server.decodeCalls.get());
                assertEquals(1, proxy.connects.get());
            } finally {
                for (String key : new String[] {"webp.sidecar.endpoint", "https.proxyHost", "https.proxyPort", "http.nonProxyHosts"}) {
                    System.clearProperty(key);
                }
            }
        }
    }

    @Test
    void classpath_tcpThroughHttpConnectProxy_completesAnRpc() throws Exception {
        readThroughProxy(new WebPImageReaderSpi());
    }

    @Test
    void modulePath_tcpThroughHttpConnectProxy_completesAnRpc() throws Exception {
        readThroughProxy(moduleLayerReaderSpi());
    }
}
