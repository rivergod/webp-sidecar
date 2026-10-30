package net.rivergod.webp.imageio;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import javax.imageio.ImageReader;
import javax.imageio.ImageWriter;
import org.junit.jupiter.api.Test;

/** ADR-0020 설정 타입과 SPI·리더·writer 단위 설정. 서버를 부르지 않는다. */
class WebPClientConfigTest {

    @Test
    void builder_nothingSet_leavesEveryItemEmpty() {
        // when
        WebPClientConfig c = WebPClientConfig.builder().build();

        // then
        assertTrue(c.endpoint().isEmpty() && c.deadlineMs().isEmpty() && c.maxPixels().isEmpty()
                && c.busyRetries().isEmpty());
    }

    @Test
    void builder_endpointWithSpaces_isTrimmed() {
        // when
        WebPClientConfig c = WebPClientConfig.builder().endpoint("  localhost:50051 ").build();

        // then
        assertEquals("localhost:50051", c.endpoint().orElseThrow());
    }

    @Test
    void builder_invalidValues_areRejected() {
        // given
        WebPClientConfig.Builder b = WebPClientConfig.builder();

        // when / then
        assertThrows(IllegalArgumentException.class, () -> b.endpoint(" "));
        assertThrows(IllegalArgumentException.class, () -> b.deadlineMs(0));
        assertThrows(IllegalArgumentException.class, () -> b.maxPixels(-1));
        assertThrows(IllegalArgumentException.class, () -> b.busyRetries(WebPClientConfig.MAX_BUSY_RETRIES + 1));
    }

    @Test
    void orElse_unsetItems_comeFromBase() {
        // given
        WebPClientConfig reader = WebPClientConfig.builder().deadlineMs(5).build();
        WebPClientConfig spi = WebPClientConfig.builder().endpoint("spi:1").deadlineMs(9).build();

        // when
        WebPClientConfig merged = reader.orElse(spi);

        // then
        assertEquals(WebPClientConfig.builder().endpoint("spi:1").deadlineMs(5).build(), merged);
    }

    @Test
    void readerSpi_defaultConstructor_hasEmptyConfig() {
        // when
        WebPImageReaderSpi spi = new WebPImageReaderSpi();

        // then
        assertSame(WebPClientConfig.EMPTY, spi.config());
    }

    @Test
    void createReaderInstance_configExtension_overridesSpiConfigPerItem() {
        // given
        WebPImageReaderSpi spi = new WebPImageReaderSpi(WebPClientConfig.builder().endpoint("spi:1").maxPixels(10).build());

        // when
        ImageReader reader = spi.createReaderInstance(WebPClientConfig.builder().maxPixels(20).build());

        // then
        assertEquals(WebPClientConfig.builder().endpoint("spi:1").maxPixels(20).build(),
                ((WebPImageReader) reader).config());
    }

    @Test
    void createReaderInstance_nullExtension_usesSpiConfig() {
        // given
        WebPClientConfig config = WebPClientConfig.builder().endpoint("spi:1").build();
        WebPImageReaderSpi spi = new WebPImageReaderSpi(config);

        // when
        ImageReader reader = spi.createReaderInstance(null);

        // then
        assertEquals(config, ((WebPImageReader) reader).config());
    }

    @Test
    void createWriterInstance_configExtension_overridesSpiConfigPerItem() {
        // given
        WebPImageWriterSpi spi = new WebPImageWriterSpi(WebPClientConfig.builder().endpoint("spi:1").build());

        // when
        ImageWriter writer = spi.createWriterInstance(WebPClientConfig.builder().busyRetries(2).build());

        // then
        assertEquals(WebPClientConfig.builder().endpoint("spi:1").busyRetries(2).build(),
                ((WebPImageWriter) writer).config());
    }

    @Test
    void defaultParams_areThePublicWebPTypes() {
        // when
        Object read = new WebPImageReaderSpi().createReaderInstance(null).getDefaultReadParam();
        Object write = new WebPImageWriterSpi().createWriterInstance(null).getDefaultWriteParam();

        // then
        assertInstanceOf(WebPReadParam.class, read);
        assertInstanceOf(WebPWriteParam.class, write);
    }

    @Test
    void readParam_negativeDeadline_isRejected() {
        // given
        WebPReadParam p = new WebPReadParam();

        // when / then
        assertThrows(IllegalArgumentException.class, () -> p.setDeadlineMs(-1));
    }

    @Test
    void toString_showsOnlySetItems() {
        // when
        String s = WebPClientConfig.builder().deadlineMs(5).build().toString();

        // then
        assertEquals("WebPClientConfig[deadlineMs=5]", s);
    }
}
