package net.rivergod.webp.imageio;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Locale;
import javax.imageio.ImageWriteParam;
import net.rivergod.webp.imageio.proto.v1.EncodeParams;
import org.junit.jupiter.api.Test;

class WebPWriteParamTest {

    @Test
    void compressionTypes_default_areLossyAndLossless() {
        // when
        String[] types = new WebPWriteParam(Locale.ROOT).getCompressionTypes();

        // then
        assertArrayEquals(new String[] {"Lossy", "Lossless"}, types);
    }

    @Test
    void toEncodeParams_explicitLosslessHalfQuality_mapsTypeAndQuality() {
        // given
        WebPWriteParam p = new WebPWriteParam(Locale.ROOT);
        p.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
        p.setCompressionType("Lossless");
        p.setCompressionQuality(0.5f);

        // when
        EncodeParams e = WebPWriteParam.toEncodeParams(p);

        // then
        assertTrue(e.getLossless());
        assertEquals(50f, e.getQuality(), 1e-6);
    }

    @Test
    void toEncodeParams_defaultMode_leavesServerDefaults() {
        // given
        WebPWriteParam p = new WebPWriteParam(Locale.ROOT);

        // when
        EncodeParams e = WebPWriteParam.toEncodeParams(p);

        // then
        assertFalse(e.getLossless());
        assertFalse(e.hasQuality());
    }

    @Test
    void isCompressionLossless_lossyType_returnsFalse() {
        // given
        WebPWriteParam p = new WebPWriteParam(Locale.ROOT);
        p.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
        p.setCompressionType("Lossy");

        // when / then
        assertFalse(p.isCompressionLossless());
    }
}
