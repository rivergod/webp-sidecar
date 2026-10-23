package net.rivergod.webp.imageio;

import com.google.protobuf.ByteString;
import javax.imageio.IIOException;
import net.rivergod.webp.imageio.proto.v1.FrameHeader;
import net.rivergod.webp.imageio.proto.v1.PixelFormat;

/**
 * Decode 응답의 RGBA8 조각을 받아 바로 int ARGB(또는 RGB) 배열로 바꾼다(ADR-0004, ADR-0013).
 *
 * <p>전체 크기의 중간 byte[]를 만들지 않는다. 조각 경계가 픽셀 중간에 걸려도 남은 바이트를 다음 조각에 이어 붙인다.
 * 헤더를 받으면 크기를 검증한 뒤에야 배열을 할당한다(클라이언트 쪽 decompression bomb 방어, docs/design/grpc-api.md §3).
 * 한 호출 전용이며 스레드 안전하지 않다(gRPC 콜백은 순서대로 한 스레드씩 부른다).
 */
final class ArgbFrameSink implements WebPClient.FrameReceiver {

    /** int[] 하나에 담을 수 있는 최대 픽셀 수(JVM 배열 길이 한도 근처). */
    static final long MAX_ARRAY_PIXELS = Integer.MAX_VALUE - 8L;

    private final int expectedWidth;
    private final int expectedHeight;
    private final long maxPixels;
    private final boolean alpha;
    private final int[] target;
    private final byte[] carry = new byte[4];
    private int carryLen;
    private byte[] scratch = new byte[0];
    private int[] argb;
    private int position;
    private long remainingBytes = -1;
    private FrameHeader header;

    /**
     * @param expectedWidth GetFeatures가 알려 준 너비. 단위: 픽셀
     * @param expectedHeight GetFeatures가 알려 준 높이. 단위: 픽셀
     * @param maxPixels 클라이언트 픽셀 상한. 0이면 JVM 배열 한도만 적용
     * @param alpha true면 ARGB(alpha 포함), false면 RGB(alpha 버림)로 채운다
     * @param target 채울 배열(width × height 길이). null이면 헤더를 받은 뒤 새로 할당한다
     */
    ArgbFrameSink(int expectedWidth, int expectedHeight, long maxPixels, boolean alpha, int[] target) {
        this.expectedWidth = expectedWidth;
        this.expectedHeight = expectedHeight;
        this.maxPixels = maxPixels;
        this.alpha = alpha;
        this.target = target;
    }

    @Override
    public void header(FrameHeader h) throws IIOException {
        if (header != null) {
            throw new IIOException("WebP server sent the frame header twice");
        }
        long pixels = (long) h.getWidth() * h.getHeight();
        if (h.getPixelFormat() != PixelFormat.PIXEL_FORMAT_RGBA8) {
            throw new IIOException("WebP server returned unsupported pixel format " + h.getPixelFormat());
        }
        if (h.getWidth() != expectedWidth || h.getHeight() != expectedHeight) {
            throw new IIOException("WebP server returned " + h.getWidth() + "x" + h.getHeight()
                    + " but GetFeatures reported " + expectedWidth + "x" + expectedHeight);
        }
        if (h.getPixelBytes() != pixels * 4) {
            throw new IIOException("WebP server declared " + h.getPixelBytes() + " pixel bytes for "
                    + h.getWidth() + "x" + h.getHeight() + " RGBA");
        }
        // 할당 전에 클라이언트 상한을 검사한다.
        if (maxPixels > 0 && pixels > maxPixels) {
            throw new IIOException("WebP image " + h.getWidth() + "x" + h.getHeight() + " exceeds client limit "
                    + maxPixels + " pixels (webp.imageio.max-pixels)");
        }
        if (pixels > MAX_ARRAY_PIXELS) {
            throw new IIOException("WebP image " + h.getWidth() + "x" + h.getHeight() + " does not fit in a Java array");
        }
        if (target != null && target.length != pixels) {
            throw new IIOException("destination buffer has " + target.length + " pixels, expected " + pixels);
        }
        argb = target != null ? target : new int[(int) pixels];
        remainingBytes = h.getPixelBytes();
        header = h;
    }

    @Override
    public void pixels(ByteString chunk) throws IIOException {
        if (header == null) {
            throw new IIOException("WebP server sent pixels before the frame header");
        }
        int n = chunk.size();
        if (n > remainingBytes) {
            throw new IIOException("WebP server sent more pixel bytes than declared");
        }
        remainingBytes -= n;
        if (scratch.length < n) {
            scratch = new byte[n];
        }
        chunk.copyTo(scratch, 0);
        byte[] b = scratch;
        int i = 0;
        // 이전 조각에서 넘어온 픽셀 조각을 먼저 채운다.
        while (carryLen > 0 && i < n) {
            carry[carryLen++] = b[i++];
            if (carryLen == 4) {
                put(carry[0], carry[1], carry[2], carry[3]);
                carryLen = 0;
            }
        }
        for (; i + 4 <= n; i += 4) {
            put(b[i], b[i + 1], b[i + 2], b[i + 3]);
        }
        while (i < n) {
            carry[carryLen++] = b[i++];
        }
    }

    private void put(byte r, byte g, byte b, byte a) {
        int rgb = ((r & 0xff) << 16) | ((g & 0xff) << 8) | (b & 0xff);
        argb[position++] = alpha ? ((a & 0xff) << 24) | rgb : rgb;
    }

    @Override
    public void finish() throws IIOException {
        if (header == null) {
            throw new IIOException("WebP server sent no frame header");
        }
        if (remainingBytes != 0 || carryLen != 0) {
            throw new IIOException("WebP server ended the pixel stream " + remainingBytes + " bytes early");
        }
    }

    /** 받은 헤더(finish 이후 유효). */
    FrameHeader frameHeader() {
        return header;
    }

    /** 채운 픽셀 배열(finish 이후 유효). */
    int[] argb() {
        return argb;
    }
}
