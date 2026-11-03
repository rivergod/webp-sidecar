package net.rivergod.webp.sidecar;

import com.google.protobuf.ByteString;
import com.google.protobuf.UnsafeByteOperations;
import net.rivergod.webp.sidecar.proto.v1.FrameHeader;
import net.rivergod.webp.sidecar.proto.v1.PixelFormat;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * ADR-0004 확인 방법: RGBA8 조각 → int ARGB 변환 비용을 잰다(JMH가 아닌 대략값). 결과는 docs/research/0008에 기록한다.
 * 실행: ./gradlew :client-jvm:udsPoc (poc 태그)
 */
@Tag("poc")
class ConversionCostPocTest {

    @Test
    void argbConversion_4kFrame_reportsTimePerFrame() throws Exception {
        // given: 3840x2160 RGBA를 1 MiB 조각으로 나눈다(서버 기본 조각 크기).
        int w = 3840;
        int h = 2160;
        byte[] rgba = new byte[w * h * 4];
        new java.util.Random(1).nextBytes(rgba);
        ByteString[] chunks = new ByteString[(rgba.length + (1 << 20) - 1) >> 20];
        for (int i = 0; i < chunks.length; i++) {
            int off = i << 20;
            chunks[i] = UnsafeByteOperations.unsafeWrap(rgba, off, Math.min(1 << 20, rgba.length - off));
        }
        FrameHeader header = FrameHeader.newBuilder().setWidth(w).setHeight(h)
                .setPixelFormat(PixelFormat.PIXEL_FORMAT_RGBA8).setPixelBytes(rgba.length).build();
        int[] target = new int[w * h];

        // when: warmup 후 평균을 잰다. 기준선은 같은 바이트 수의 메모리 복사.
        for (boolean alpha : new boolean[] {true, false}) {
            for (int i = 0; i < 20; i++) {
                convert(header, chunks, alpha, target);
            }
            int n = 50;
            long start = System.nanoTime();
            for (int i = 0; i < n; i++) {
                convert(header, chunks, alpha, target);
            }
            double ms = (System.nanoTime() - start) / 1e6 / n;
            System.out.printf("CONVERSION alpha=%s: %.2f ms per 3840x2160 frame (%.2f ns/pixel)%n", alpha, ms, ms * 1e6 / (w * h));
        }
        byte[] copy = new byte[rgba.length];
        for (int i = 0; i < 20; i++) {
            System.arraycopy(rgba, 0, copy, 0, rgba.length);
        }
        long start = System.nanoTime();
        for (int i = 0; i < 50; i++) {
            System.arraycopy(rgba, 0, copy, 0, rgba.length);
        }
        System.out.printf("CONVERSION baseline arraycopy 33 MB: %.2f ms%n", (System.nanoTime() - start) / 1e6 / 50);
    }

    private static void convert(FrameHeader header, ByteString[] chunks, boolean alpha, int[] target) throws Exception {
        ArgbFrameSink sink = new ArgbFrameSink(header.getWidth(), header.getHeight(), 0, alpha, target);
        sink.header(header);
        for (ByteString c : chunks) {
            sink.pixels(c);
        }
        sink.finish();
    }
}
