package net.rivergod.webp.imageio;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.Point;
import java.awt.Rectangle;
import java.nio.charset.StandardCharsets;
import javax.imageio.ImageReadParam;
import org.junit.jupiter.api.Test;

/** GetFeatures를 생략하는 정지 이미지 경로의 판단 조건(Phase 5-2). */
class WebPImageReaderFastPathTest {

    /** RIFF 헤더 + 첫 청크 id + (VP8X면) 플래그 바이트. 길이는 판단에 필요한 21바이트 이상. */
    private static byte[] head(String chunk, int vp8xFlags) {
        byte[] b = new byte[30];
        System.arraycopy("RIFF".getBytes(StandardCharsets.US_ASCII), 0, b, 0, 4);
        System.arraycopy("WEBP".getBytes(StandardCharsets.US_ASCII), 0, b, 8, 4);
        System.arraycopy(chunk.getBytes(StandardCharsets.US_ASCII), 0, b, 12, 4);
        b[20] = (byte) vp8xFlags;
        return b;
    }

    @Test
    void isLocalStill_simpleLossyAndLossless_isTrue() {
        assertTrue(WebPImageReader.isLocalStill(head("VP8 ", 0)));
        assertTrue(WebPImageReader.isLocalStill(head("VP8L", 0)));
    }

    @Test
    void isLocalStill_extendedWithoutAnimationFlag_isTrue() {
        // ICC(0x20) + alpha(0x10) + EXIF(0x08) + XMP(0x04), 애니메이션 비트(0x02) 없음
        assertTrue(WebPImageReader.isLocalStill(head("VP8X", 0x3C)));
    }

    @Test
    void isLocalStill_extendedWithAnimationFlag_isFalse() {
        assertFalse(WebPImageReader.isLocalStill(head("VP8X", 0x12)));
    }

    @Test
    void isLocalStill_notWebpOrTooShort_isFalse() {
        byte[] riffAvi = head("VP8 ", 0);
        riffAvi[8] = 'A';
        assertFalse(WebPImageReader.isLocalStill(riffAvi));
        assertFalse(WebPImageReader.isLocalStill(head("ALPH", 0)));
        assertFalse(WebPImageReader.isLocalStill(new byte[20]));
    }

    @Test
    void isPlainParam_nullOrDefault_isTrue() {
        assertTrue(WebPImageReader.isPlainParam(null));
        assertTrue(WebPImageReader.isPlainParam(new ImageReadParam()));
    }

    @Test
    void isPlainParam_regionSubsamplingOrOffset_isFalse() {
        ImageReadParam region = new ImageReadParam();
        region.setSourceRegion(new Rectangle(0, 0, 2, 2));
        ImageReadParam subsampling = new ImageReadParam();
        subsampling.setSourceSubsampling(2, 2, 0, 0);
        ImageReadParam offset = new ImageReadParam();
        offset.setDestinationOffset(new Point(1, 0));

        assertFalse(WebPImageReader.isPlainParam(region));
        assertFalse(WebPImageReader.isPlainParam(subsampling));
        assertFalse(WebPImageReader.isPlainParam(offset));
    }
}
