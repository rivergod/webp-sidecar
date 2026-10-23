package net.rivergod.webp.imageio.bench;

import java.awt.image.BufferedImage;
import java.awt.image.DataBufferInt;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.Locale;
import javax.imageio.ImageReader;
import javax.imageio.spi.ImageReaderSpi;
import javax.imageio.stream.ImageInputStream;
import javax.imageio.stream.MemoryCacheImageInputStream;
import net.rivergod.webp.imageio.bench.wasm.WasmWebPDecoder;

/**
 * 비교 대상 디코더(ADR-0011). 모두 "메모리의 WebP 바이트 → {@link BufferedImage}"를 한다.
 *
 * <p>ImageIO 플러그인은 {@code ImageIO.read}와 같은 방식으로 쓴다: 호출마다 리더를 새로 만들고, {@code setInput(stream,
 * seekForwardOnly=true, ignoreMetadata=true)}, {@code read(0)}, {@code dispose()}. 리더는 레지스트리가 아니라 각 플러그인의
 * SPI 클래스에서 직접 만든다(같은 클래스패스에 WebP 플러그인이 셋 있으므로).
 */
public final class Decoders {

    /** 디코더 하나. 스레드 안전하지 않다(JMH {@code Scope.Thread}로 쓴다). */
    public interface Decoder {
        /**
         * 디코딩한다.
         *
         * @param webp WebP 파일 전체
         * @return 디코딩한 이미지
         * @throws IOException 디코딩 실패
         */
        BufferedImage decode(byte[] webp) throws IOException;
    }

    /** 비교 대상. JMH 파라미터 값은 이 이름이다. */
    public enum Impl {
        /** 이 프로젝트의 플러그인. 서버 엔드포인트는 시스템 프로퍼티 {@code webp.imageio.endpoint}. */
        SIDECAR,
        /** TwelveMonkeys imageio-webp(순수 Java). */
        TWELVEMONKEYS,
        /** darkxanter webp-imageio(JNI, libwebp 내장). */
        DARKXANTER,
        /** libwebp Wasm + Chicory 빌드타임 컴파일(ADR-0015 판정용 Wasm 수치). */
        WASM,
        /** libwebp Wasm + Chicory 인터프리터(참고값). */
        WASM_INTERPRETER;

        /**
         * 파라미터 문자열(대소문자 무시)로 찾는다.
         *
         * @param name 이름
         * @return 비교 대상
         */
        public static Impl of(String name) {
            return valueOf(name.toUpperCase(Locale.ROOT));
        }

        /**
         * 새 디코더를 만든다. Wasm은 인스턴스(선형 메모리) 하나를 만들어 계속 쓴다.
         *
         * @return 디코더
         */
        public Decoder create() {
            return switch (this) {
                case SIDECAR -> imageIo(new net.rivergod.webp.imageio.WebPImageReaderSpi());
                case TWELVEMONKEYS -> imageIo(new com.twelvemonkeys.imageio.plugins.webp.WebPImageReaderSpi());
                case DARKXANTER -> imageIo(new com.luciad.imageio.webp.WebPImageReaderSpi());
                case WASM -> wasm(WasmWebPDecoder.Mode.BUILD_TIME_COMPILED);
                case WASM_INTERPRETER -> wasm(WasmWebPDecoder.Mode.INTERPRETER);
            };
        }
    }

    private Decoders() {}

    static Decoder imageIo(ImageReaderSpi spi) {
        return webp -> {
            ImageReader reader = spi.createReaderInstance();
            try (ImageInputStream in = new MemoryCacheImageInputStream(new ByteArrayInputStream(webp))) {
                reader.setInput(in, true, true);
                return reader.read(0, null);
            } finally {
                reader.dispose();
            }
        };
    }

    static Decoder wasm(WasmWebPDecoder.Mode mode) {
        WasmWebPDecoder decoder = new WasmWebPDecoder(mode);
        return webp -> {
            WasmWebPDecoder.Rgba rgba = decoder.decode(webp);
            BufferedImage img = new BufferedImage(rgba.width(), rgba.height(), BufferedImage.TYPE_INT_ARGB);
            rgbaToArgb(rgba.pixels(), ((DataBufferInt) img.getRaster().getDataBuffer()).getData());
            return img;
        };
    }

    /**
     * RGBA8(비-premultiplied) → int ARGB. 이 프로젝트 클라이언트(ArgbFrameSink)와 같은 변환이다.
     *
     * @param rgba 입력, 길이 {@code 4 × argb.length}
     * @param argb 출력
     */
    static void rgbaToArgb(byte[] rgba, int[] argb) {
        for (int i = 0, j = 0; i < argb.length; i++, j += 4) {
            argb[i] = (rgba[j + 3] & 0xFF) << 24 | (rgba[j] & 0xFF) << 16 | (rgba[j + 1] & 0xFF) << 8 | (rgba[j + 2] & 0xFF);
        }
    }
}
