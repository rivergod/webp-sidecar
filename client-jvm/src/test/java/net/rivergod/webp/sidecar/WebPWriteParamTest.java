package net.rivergod.webp.sidecar;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Locale;
import javax.imageio.ImageWriteParam;
import net.rivergod.webp.sidecar.proto.v1.EncodeParams;
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

    @Test
    void toEncodeParams_methodSetInDefaultMode_passesMethodOnly() {
        // given: method는 압축 모드와 상관없이 넘긴다(ADR-0020)
        WebPWriteParam p = new WebPWriteParam(Locale.ROOT);
        p.setMethod(6);

        // when
        EncodeParams e = WebPWriteParam.toEncodeParams(p);

        // then
        assertEquals(6, e.getMethod());
        assertFalse(e.hasQuality());
    }

    @Test
    void toEncodeParams_methodUnset_leavesServerDefault() {
        // given
        WebPWriteParam p = new WebPWriteParam(Locale.ROOT);

        // when
        EncodeParams e = WebPWriteParam.toEncodeParams(p);

        // then
        assertFalse(e.hasMethod());
    }

    @Test
    void setMethod_outOfRange_isRejected() {
        // given
        WebPWriteParam p = new WebPWriteParam(Locale.ROOT);

        // when / then
        assertThrows(IllegalArgumentException.class, () -> p.setMethod(7));
    }
}
