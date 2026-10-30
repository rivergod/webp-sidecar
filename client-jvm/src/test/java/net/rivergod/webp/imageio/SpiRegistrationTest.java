package net.rivergod.webp.imageio;

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
        Configuration cf = parent.configuration().resolve(finder, ModuleFinder.of(), Set.of("net.rivergod.webp.imageio"));
        ModuleLayer layer = parent.defineModulesWithOneLoader(cf, ClassLoader.getPlatformClassLoader());

        // when
        boolean reader = StreamSupport.stream(java.util.ServiceLoader.load(layer, ImageReaderSpi.class).spliterator(), false)
                .anyMatch(s -> s.getClass().getName().equals("net.rivergod.webp.imageio.WebPImageReaderSpi")
                        && s.getClass().getModule().getName().equals("net.rivergod.webp.imageio"));
        boolean writer = StreamSupport.stream(java.util.ServiceLoader.load(layer, ImageWriterSpi.class).spliterator(), false)
                .anyMatch(s -> s.getClass().getName().equals("net.rivergod.webp.imageio.WebPImageWriterSpi"));

        // then
        assertTrue(reader && writer);
    }

    @Test
    void modulePath_readerFromModuleLayer_completesAnRpc() throws Exception {
        // given: module path 레이어의 SPI로 만든 리더가 실제로 서버를 부른다(netty 모듈 해석 확인). 엔드포인트는 가짜 서버.
        Path[] paths = Arrays.stream(System.getProperty("webp.test.modulePath").split(File.pathSeparator))
                .map(Path::of).toArray(Path[]::new);
        ModuleLayer parent = ModuleLayer.boot();
        Configuration cf = parent.configuration()
                .resolve(ModuleFinder.of(paths), ModuleFinder.of(), Set.of("net.rivergod.webp.imageio"));
        ModuleLayer layer = parent.defineModulesWithOneLoader(cf, ClassLoader.getPlatformClassLoader());
        ImageReaderSpi spi = StreamSupport.stream(java.util.ServiceLoader.load(layer, ImageReaderSpi.class).spliterator(), false)
                .filter(s -> s.getClass().getName().equals("net.rivergod.webp.imageio.WebPImageReaderSpi"))
                .findFirst().orElseThrow();
        byte[] still = Files.readAllBytes(TestImages.SAMPLES.resolve("generated/still/lossy_17x9.webp"));
        try (FakeWebPServer server = FakeWebPServer.start()) {
            System.setProperty("webp.imageio.endpoint", server.endpoint());
            try {
                ImageReader reader = spi.createReaderInstance();
                reader.setInput(ImageIO.createImageInputStream(new ByteArrayInputStream(still)));

                // when
                BufferedImage image = reader.read(0);

                // then
                assertEquals(1, image.getWidth());
                assertEquals(1, server.decodeCalls.get());
                reader.dispose();
            } finally {
                System.clearProperty("webp.imageio.endpoint");
            }
        }
    }
}
