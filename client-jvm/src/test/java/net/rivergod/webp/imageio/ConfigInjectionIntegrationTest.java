package net.rivergod.webp.imageio;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.image.BufferedImage;
import java.io.File;
import java.util.Map;
import javax.imageio.IIOException;
import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * ADR-0020 리더 단위 설정: SPI 하나로 서버 두 개를 리더마다 골라 쓴다(예: 테넌트별 서버). 두 서버는 픽셀 상한이 달라
 * 어느 서버가 답했는지 결과로 구분된다. A는 UDS, B는 TCP로 붙는다.
 */
@Tag("integration")
class ConfigInjectionIntegrationTest {

    private static ServerContainer serverA;
    private static ServerContainer serverB;

    @BeforeAll
    static void startServers() throws Exception {
        serverA = ServerContainer.startNew();
        // B는 100픽셀보다 큰 이미지를 거부한다.
        serverB = ServerContainer.startNew(Map.of("WEBP_SERVER_MAX_PIXELS", "100"));
    }

    @AfterAll
    static void stopServers() {
        serverA.stop();
        serverB.stop();
    }

    private static BufferedImage read(WebPImageReaderSpi spi, WebPClientConfig readerConfig, File file) throws Exception {
        ImageReader reader = spi.createReaderInstance(readerConfig);
        try {
            reader.setInput(ImageIO.createImageInputStream(file));
            return reader.read(0);
        } finally {
            reader.dispose();
        }
    }

    @Test
    void readerConfig_twoServers_eachReaderUsesItsOwnEndpoint() throws Exception {
        // given: 17×9(153픽셀) 이미지. SPI는 하나고 리더마다 엔드포인트를 준다
        WebPImageReaderSpi spi = new WebPImageReaderSpi();
        File file = TestImages.SAMPLES.resolve("generated/still/lossy_17x9.webp").toFile();
        WebPClientConfig toA = WebPClientConfig.builder().endpoint(serverA.udsEndpoint()).build();
        WebPClientConfig toB = WebPClientConfig.builder().endpoint(serverB.tcpEndpoint()).build();

        // when
        BufferedImage fromA = read(spi, toA, file);
        IIOException fromB = assertThrows(IIOException.class, () -> read(spi, toB, file));

        // then
        assertEquals(17, fromA.getWidth());
        assertTrue(fromB.getMessage().contains("exceeds server limit"), fromB.getMessage());
    }

    @Test
    void registeredSpiConfig_isUsedWhenReaderConfigLeavesEndpointUnset() throws Exception {
        // given: SPI는 B를 가리키고, 리더 설정은 데드라인만 준다
        WebPImageReaderSpi spi = new WebPImageReaderSpi(
                WebPClientConfig.builder().endpoint(serverB.tcpEndpoint()).build());
        File file = TestImages.SAMPLES.resolve("generated/still/lossy_17x9.webp").toFile();

        // when
        IIOException e = assertThrows(IIOException.class,
                () -> read(spi, WebPClientConfig.builder().deadlineMs(10_000).build(), file));

        // then: B의 상한에 걸렸다
        assertTrue(e.getMessage().contains("exceeds server limit"), e.getMessage());
    }
}
