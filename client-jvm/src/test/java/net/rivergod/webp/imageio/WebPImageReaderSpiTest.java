package net.rivergod.webp.imageio;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import javax.imageio.stream.ImageInputStream;
import javax.imageio.stream.MemoryCacheImageInputStream;
import org.junit.jupiter.api.Test;

class WebPImageReaderSpiTest {

    private final WebPImageReaderSpi spi = new WebPImageReaderSpi();

    private static ImageInputStream streamOf(String ascii) {
        return new MemoryCacheImageInputStream(new ByteArrayInputStream(ascii.getBytes(StandardCharsets.ISO_8859_1)));
    }

    @Test
    void canDecodeInput_riffWebpHeader_returnsTrue() throws IOException {
        // given
        ImageInputStream in = streamOf("RIFF\u0010\u0000\u0000\u0000WEBPVP8 ");

        // when
        boolean result = spi.canDecodeInput(in);

        // then
        assertTrue(result);
    }

    @Test
    void canDecodeInput_riffWithoutWebpFourcc_returnsFalse() throws IOException {
        // given
        ImageInputStream in = streamOf("RIFF\u0010\u0000\u0000\u0000AVI LIST");

        // when
        boolean result = spi.canDecodeInput(in);

        // then
        assertFalse(result);
    }

    @Test
    void canDecodeInput_shorterThanHeader_returnsFalse() throws IOException {
        // given
        ImageInputStream in = streamOf("RIFF\u0010\u0000");

        // when
        boolean result = spi.canDecodeInput(in);

        // then
        assertFalse(result);
    }

    @Test
    void canDecodeInput_anyInput_restoresStreamPosition() throws IOException {
        // given
        ImageInputStream in = streamOf("xxRIFF\u0010\u0000\u0000\u0000WEBP");
        in.skipBytes(2);

        // when
        spi.canDecodeInput(in);

        // then
        assertEquals(2, in.getStreamPosition());
    }

    @Test
    void canDecodeInput_nonStreamSource_returnsFalse() throws IOException {
        // when
        boolean result = spi.canDecodeInput("RIFF....WEBP");

        // then
        assertFalse(result);
    }
}
