package net.rivergod.webp.imageio;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.protobuf.ByteString;
import javax.imageio.IIOException;
import net.rivergod.webp.imageio.proto.v1.FrameHeader;
import net.rivergod.webp.imageio.proto.v1.PixelFormat;
import org.junit.jupiter.api.Test;

class ArgbFrameSinkTest {

    private static FrameHeader header(int w, int h, long bytes) {
        return FrameHeader.newBuilder().setWidth(w).setHeight(h).setPixelFormat(PixelFormat.PIXEL_FORMAT_RGBA8)
                .setPixelBytes(bytes).build();
    }

    private static final byte[] TWO_PIXELS = {0x11, 0x22, 0x33, 0x44, 0x55, 0x66, 0x77, (byte) 0x88};

    @Test
    void pixels_chunkSplitInsidePixel_reassemblesArgb() throws IIOException {
        // given
        ArgbFrameSink sink = new ArgbFrameSink(2, 1, 0, true, null);
        sink.header(header(2, 1, 8));
        sink.pixels(ByteString.copyFrom(TWO_PIXELS, 0, 3));
        sink.pixels(ByteString.copyFrom(TWO_PIXELS, 3, 4));

        // when
        sink.pixels(ByteString.copyFrom(TWO_PIXELS, 7, 1));

        // then
        sink.finish();
        assertArrayEquals(new int[] {0x44112233, 0x88556677}, sink.argb());
    }

    @Test
    void pixels_opaqueMode_dropsAlpha() throws IIOException {
        // given
        ArgbFrameSink sink = new ArgbFrameSink(2, 1, 0, false, null);
        sink.header(header(2, 1, 8));

        // when
        sink.pixels(ByteString.copyFrom(TWO_PIXELS));

        // then
        assertArrayEquals(new int[] {0x00112233, 0x00556677}, sink.argb());
    }

    @Test
    void header_overClientMaxPixels_rejectsBeforeAllocating() {
        // given
        ArgbFrameSink sink = new ArgbFrameSink(1000, 1000, 10, true, null);

        // when
        IIOException e = assertThrows(IIOException.class, () -> sink.header(header(1000, 1000, 4_000_000)));

        // then
        assertTrue(e.getMessage().contains("client limit"), e.getMessage());
        assertNull(sink.argb());
    }

    @Test
    void header_pixelBytesMismatch_isRejected() {
        // given
        ArgbFrameSink sink = new ArgbFrameSink(2, 1, 0, true, null);

        // when / then
        assertThrows(IIOException.class, () -> sink.header(header(2, 1, 9)));
    }

    @Test
    void header_sizeDiffersFromFeatures_isRejected() {
        // given
        ArgbFrameSink sink = new ArgbFrameSink(2, 1, 0, true, null);

        // when / then
        assertThrows(IIOException.class, () -> sink.header(header(1, 2, 8)));
    }

    @Test
    void finish_streamEndsEarly_isRejected() throws IIOException {
        // given
        ArgbFrameSink sink = new ArgbFrameSink(2, 1, 0, true, null);
        sink.header(header(2, 1, 8));
        sink.pixels(ByteString.copyFrom(TWO_PIXELS, 0, 4));

        // when / then
        assertThrows(IIOException.class, sink::finish);
    }

    @Test
    void pixels_moreThanDeclared_isRejected() throws IIOException {
        // given
        ArgbFrameSink sink = new ArgbFrameSink(1, 1, 0, true, null);
        sink.header(header(1, 1, 4));

        // when / then
        assertThrows(IIOException.class, () -> sink.pixels(ByteString.copyFrom(TWO_PIXELS)));
    }
}
