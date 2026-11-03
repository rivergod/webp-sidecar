package net.rivergod.webp.sidecar;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.time.Duration;
import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageOutputStream;
import javax.imageio.stream.MemoryCacheImageInputStream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * 한 JVM의 요청들은 엔드포인트당 HTTP/2 연결 하나를 같이 쓴다. 한 스트림이 받은 데이터를 읽지 않고 멈춰 있어도 다른 요청이
 * 끝나야 한다(연결 단위 흐름 제어 교착 확인, docs/research/0012-gif-conversion-load.md).
 */
@Tag("integration")
class FlowControlIntegrationTest {

    private static final int WIDTH = 2048;
    private static final int HEIGHT = 1536;

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

    @Test
    void decode_whileAnimationStreamIsPausedOnSameConnection_completes() throws Exception {
        // given: 프레임마다 RGBA 12 MiB인 애니메이션의 0번만 읽고 멈춘다. 순차 읽기 스트림은 열린 채이고 서버는 다음 프레임을 보낸다
        byte[] animation = writeAnimation(3);
        byte[] still = writeAnimation(1);
        ImageReader paused = reader(animation);
        paused.read(0);

        // when: 같은 연결로 다른 큰 이미지를 읽는다
        BufferedImage image = assertTimeoutPreemptively(Duration.ofSeconds(20), () -> reader(still).read(0));

        // then
        assertEquals(WIDTH, image.getWidth());
        assertEquals(WIDTH, paused.read(1).getWidth());
        paused.dispose();
    }

    private static ImageReader reader(byte[] webp) {
        ImageReader reader = ImageIO.getImageReadersByFormatName("webp").next();
        reader.setInput(new MemoryCacheImageInputStream(new ByteArrayInputStream(webp)));
        return reader;
    }

    /** 프레임마다 다른 위치에 사각형을 그린 애니메이션(프레임 1개면 정지 이미지). */
    private static byte[] writeAnimation(int frames) throws Exception {
        ImageWriter writer = ImageIO.getImageWritersByFormatName("webp").next();
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ImageOutputStream out = ImageIO.createImageOutputStream(bytes)) {
            writer.setOutput(out);
            if (frames == 1) {
                writer.write(frame(0));
            } else {
                writer.prepareWriteSequence(null);
                for (int i = 0; i < frames; i++) {
                    writer.writeToSequence(new IIOImage(frame(i), null, null), null);
                }
                writer.endWriteSequence();
            }
        } finally {
            writer.dispose();
        }
        return bytes.toByteArray();
    }

    private static BufferedImage frame(int i) {
        BufferedImage image = TestImages.pattern(WIDTH, HEIGHT, false);
        Graphics2D g = image.createGraphics();
        g.setColor(Color.MAGENTA);
        g.fillRect(100 + 200 * i, 100, 150, 150);
        g.dispose();
        return image;
    }
}
