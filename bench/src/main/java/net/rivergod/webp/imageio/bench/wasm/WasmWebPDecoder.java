package net.rivergod.webp.imageio.bench.wasm;

import com.dylibso.chicory.compiler.MachineFactoryCompiler;
import com.dylibso.chicory.runtime.ByteArrayMemory;
import com.dylibso.chicory.runtime.ExportFunction;
import com.dylibso.chicory.runtime.Instance;
import com.dylibso.chicory.runtime.Machine;
import com.dylibso.chicory.runtime.Memory;
import com.dylibso.chicory.wasm.Parser;
import com.dylibso.chicory.wasm.WasmModule;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.Objects;
import java.util.function.Function;

/**
 * libwebp v1.6.0을 Wasm으로 빌드해 Chicory(순수 Java Wasm 런타임)로 JVM 안에서 실행하는 정지 이미지 디코더다.
 *
 * <p><b>벤치마크 전용이다.</b> 사이드카(gRPC 서버)와 비교하는 경쟁 대안(ADR-0001 선택지 5)을 재는 용도이고 제품 코드가
 * 아니다. 측정 조건은 ADR-0015를 따른다: 판정용 수치는 {@link Mode#BUILD_TIME_COMPILED}로 재고, {@link
 * Mode#INTERPRETER}는 참고값이다.
 *
 * <p>출력은 ADR-0004의 RGBA8(채널당 8비트, 바이트 순서 R,G,B,A, 비-premultiplied, 행 패딩 없음 = stride
 * {@code width × 4})이다.
 *
 * <p>Wasm 모듈(bench/wasm/shim.c)은 호스트 import가 없다. libwebp 안의 메모리 오류는 선형 메모리 밖으로 나가지 못하고
 * Wasm trap이 되어 {@link com.dylibso.chicory.wasm.ChicoryException}(unchecked)으로 올라온다. trap이나 {@link
 * OutOfMemoryError}가 나면 Wasm 쪽 버퍼를 돌려주지 않고 예외를 그대로 올린다. 그 뒤의 인스턴스 상태는 믿을 수 없으므로 그 디코더
 * 객체는 버린다.
 *
 * <h2>필요한 클래스와 리소스</h2>
 *
 * <ul>
 *   <li>{@link Mode#BUILD_TIME_COMPILED}: Chicory 빌드타임 컴파일러가 만든 {@code LibWebPModule}(같은 패키지)과 그
 *       리소스 {@code LibWebPModule.meta}. 실행 시 의존성은 {@code com.dylibso.chicory:runtime}, {@code wasm}뿐이다.
 *   <li>{@link Mode#RUNTIME_COMPILED}, {@link Mode#INTERPRETER}: 같은 패키지의 클래스패스 리소스 {@code
 *       libwebp.wasm}(원본 모듈). RUNTIME_COMPILED는 {@code com.dylibso.chicory:compiler}와 ASM도 필요하다.
 * </ul>
 *
 * <h2>스레드 안전성</h2>
 *
 * <p>스레드 안전하지 않다. 객체 하나가 Wasm 인스턴스 하나(선형 메모리, 실행 스택)를 가진다. 스레드마다 객체를 따로 만든다(JMH라면
 * {@code Scope.Thread} 상태). 모드별로 한 번만 만드는 정적 캐시(파싱한 모듈, 런타임 컴파일 결과)는 클래스 초기화로 안전하게
 * 공개되고 읽기만 하므로 여러 객체가 공유한다.
 *
 * <h2>메모리</h2>
 *
 * <p>선형 메모리는 {@link ByteArrayMemory}로 JVM 힙 안에 64 KiB {@code byte[]} 페이지로 잡히므로 {@code -Xmx}에 포함된다.
 * 모듈이 선언한 최대 크기는 1 GiB(16384 페이지)이고, Wasm 메모리는 줄어들지 않아 객체가 살아 있는 동안 가장 큰 디코딩 때의 크기를
 * 유지한다. 호출마다 입력 복사(Java → Wasm)와 출력 복사(Wasm → Java) 한 번씩이 생긴다.
 */
public final class WasmWebPDecoder {

    /** Chicory 실행 방식. */
    public enum Mode {
        /**
         * Chicory 빌드타임 컴파일러가 Wasm 함수를 미리 JVM 바이트코드로 바꾼 클래스({@code LibWebPModule})를 쓴다. ADR-0015 판정용
         * 측정 대상이다.
         */
        BUILD_TIME_COMPILED,
        /**
         * 같은 컴파일러를 실행 시점에 돌린다(첫 사용 때 한 번 컴파일). 빌드타임 경로를 쓸 수 없을 때를 위한 참고값이다.
         */
        RUNTIME_COMPILED,
        /** Chicory 인터프리터. ADR-0015 참고값이다. */
        INTERPRETER
    }

    /**
     * 디코딩을 허용하는 최대 픽셀 수(너비 × 높이). 서버 기본 상한(ADR-0012, 8192 × 8192)과 같게 둬서 같은 입력 집합을 비교한다. 이
     * 크기의 lossless 디코딩이 모듈 최대 메모리(1 GiB) 안에 들어가는 것을 확인했다(bench/wasm/README.md).
     */
    public static final long MAX_PIXELS = 8192L * 8192L;

    private static final int BYTES_PER_PIXEL = 4;

    private final Mode mode;
    private final Memory memory;
    private final ExportFunction alloc;
    private final ExportFunction free;
    private final ExportFunction getInfo;
    private final ExportFunction decodeRgbaInto;
    private final int decoderVersion;
    // wp_get_info의 너비·높이 출력 자리(int32 두 개, 8바이트). 객체 수명 동안 한 번만 잡는다.
    private final int infoScratch;

    /**
     * 새 Wasm 인스턴스를 만들고 초기화한다.
     *
     * <p>{@link Mode#RUNTIME_COMPILED}는 처음 만들 때 모듈 전체를 컴파일하므로 첫 호출이 느리다(이후 객체는 결과를 재사용한다).
     *
     * @param mode 실행 방식
     * @throws NullPointerException mode가 null일 때
     * @throws UncheckedIOException 필요한 Wasm 리소스를 읽지 못했을 때
     * @throws com.dylibso.chicory.wasm.InvalidException 모듈에 예상한 export({@code _initialize}, {@code wp_*})가 없을 때
     * @throws IllegalStateException 초기 할당(8바이트)이 실패했을 때
     */
    public WasmWebPDecoder(Mode mode) {
        this.mode = Objects.requireNonNull(mode, "mode");
        Instance instance = newInstance(mode);

        // reactor 모듈(-mexec-model=reactor): 다른 export보다 _initialize(정적 생성자)를 먼저 한 번 불러야 한다.
        // Chicory는 _start만 자동으로 부르고 _initialize는 부르지 않는다.
        instance.export("_initialize").apply();

        this.memory = instance.memory();
        this.alloc = instance.export("wp_alloc");
        this.free = instance.export("wp_free");
        this.getInfo = instance.export("wp_get_info");
        this.decodeRgbaInto = instance.export("wp_decode_rgba_into");
        this.decoderVersion = (int) instance.export("wp_decoder_version").apply()[0];
        this.infoScratch = allocate(2 * Integer.BYTES);
    }

    /**
     * 헤더만 읽어 이미지 크기를 돌려준다. 픽셀 버퍼는 할당하지 않는다.
     *
     * @param webp WebP 파일 전체 바이트
     * @return {@code {너비, 높이}}, 단위 픽셀
     * @throws NullPointerException webp가 null일 때
     * @throws IllegalArgumentException WebP가 아니거나 헤더가 깨졌을 때
     * @throws IllegalStateException Wasm 메모리에 입력을 복사할 공간이 없을 때
     */
    public int[] info(byte[] webp) {
        Objects.requireNonNull(webp, "webp");
        int in = copyIn(webp);
        int[] size = readInfoOrNull(in, webp.length);
        free.apply(in);
        if (size == null) {
            throw new IllegalArgumentException("not a WebP image or corrupted header");
        }
        return size;
    }

    /**
     * WebP 정지 이미지를 RGBA8로 디코딩한다(애니메이션은 첫 프레임이 아니라 오류다 — libwebp {@code WebPDecode} 동작).
     *
     * @param webp WebP 파일 전체 바이트
     * @return RGBA8 픽셀, 길이 {@code 너비 × 높이 × 4} 바이트, stride {@code 너비 × 4}
     * @throws NullPointerException webp가 null일 때
     * @throws IllegalArgumentException WebP가 아니거나, 픽셀 수가 {@link #MAX_PIXELS}를 넘거나, 디코딩이 실패했을 때.
     *     libwebp 내부 버퍼 할당이 선형 메모리 최대 크기에 막힌 경우도 shim ABI로는 구분되지 않아 이 예외가 된다
     * @throws IllegalStateException Wasm 선형 메모리 최대 크기(1 GiB) 때문에 입력·출력 버퍼를 잡지 못했을 때
     * @throws OutOfMemoryError 선형 메모리 페이지나 결과 배열을 JVM 힙에 잡지 못했을 때(이 객체는 버린다)
     */
    public byte[] decodeRgba(byte[] webp) {
        return decode(webp).pixels();
    }

    /**
     * RGBA8 디코딩 결과.
     *
     * @param width 너비, 단위 픽셀
     * @param height 높이, 단위 픽셀
     * @param pixels RGBA8 픽셀, 길이 {@code width × height × 4}, stride {@code width × 4}
     */
    public record Rgba(int width, int height, byte[] pixels) {}

    /**
     * {@link #decodeRgba}와 같고, 크기를 함께 돌려준다.
     *
     * @param webp WebP 파일 전체 바이트
     * @return 크기와 RGBA8 픽셀
     * @throws NullPointerException webp가 null일 때
     * @throws IllegalArgumentException {@link #decodeRgba}와 같다
     * @throws IllegalStateException {@link #decodeRgba}와 같다
     * @throws OutOfMemoryError {@link #decodeRgba}와 같다
     */
    public Rgba decode(byte[] webp) {
        Objects.requireNonNull(webp, "webp");
        // 버퍼 해제는 정상 경로와 입력 오류 경로에서만 한다. trap이나 OutOfMemoryError 뒤에는 인스턴스를 더 호출하지 않아야
        // 원래 예외가 가려지지 않는다(그 객체는 버린다).
        int in = copyIn(webp);
        int[] size = readInfoOrNull(in, webp.length);
        if (size == null) {
            free.apply(in);
            throw new IllegalArgumentException("not a WebP image or corrupted header");
        }
        int width = size[0];
        int height = size[1];

        // 출력 버퍼를 잡기 전에 픽셀 수 상한을 확인한다. 거대 크기 선언(bomb)을 할당 전에 거부한다.
        long pixels = (long) width * height;
        if (pixels > MAX_PIXELS) {
            free.apply(in);
            throw new IllegalArgumentException(
                    "too many pixels: " + width + "x" + height + " > " + MAX_PIXELS);
        }
        int stride = width * BYTES_PER_PIXEL;
        int outSize = Math.toIntExact(pixels * BYTES_PER_PIXEL);

        int out = (int) alloc.apply(outSize)[0];
        if (out == 0) {
            free.apply(in);
            throw outOfWasmMemory(outSize);
        }
        // libwebp 내부 할당 실패(선형 메모리 최대 크기 도달)도 여기서는 디코딩 실패(0)로 보인다.
        long ok = decodeRgbaInto.apply(in, webp.length, out, outSize, stride)[0];
        byte[] rgba = ok != 0 ? memory.readBytes(out, outSize) : null;
        free.apply(out);
        free.apply(in);
        if (rgba == null) {
            throw new IllegalArgumentException(
                    "WebP decode failed (corrupted input or wasm memory exhausted): "
                            + width
                            + "x"
                            + height
                            + ", linearMemoryBytes="
                            + linearMemoryBytes());
        }
        return new Rgba(width, height, rgba);
    }

    /**
     * 링크된 libwebp 디코더 버전.
     *
     * @return {@code 0xMMmmrr} 형식(1.6.0 → {@code 0x010600})
     */
    public int decoderVersion() {
        return decoderVersion;
    }

    /**
     * 이 객체의 실행 방식.
     *
     * @return 생성할 때 준 모드
     */
    public Mode mode() {
        return mode;
    }

    /**
     * 현재 Wasm 선형 메모리 크기. 메모리는 줄어들지 않으므로 지금까지의 최대 사용량(최고 수위)과 같다.
     *
     * @return 바이트 수(64 KiB 페이지 수 × 65536)
     */
    public long linearMemoryBytes() {
        return (long) memory.pages() * Memory.PAGE_SIZE;
    }

    private static Instance newInstance(Mode mode) {
        Instance.Builder builder;
        switch (mode) {
            case BUILD_TIME_COMPILED:
                builder = Instance.builder(LibWebPModule.load()).withMachineFactory(LibWebPModule::create);
                break;
            case RUNTIME_COMPILED:
                builder = Instance.builder(FullModule.MODULE).withMachineFactory(RuntimeCompiled.FACTORY);
                break;
            case INTERPRETER:
                // machine factory를 주지 않으면 Chicory 기본값인 InterpreterMachine을 쓴다.
                builder = Instance.builder(FullModule.MODULE);
                break;
            default:
                throw new AssertionError(mode);
        }
        // Chicory 문서는 OpenJDK에서 ByteArrayMemory를 권장한다(기본값은 ByteBufferMemory).
        // _start가 없는 reactor 모듈이라 withStart(false)는 의미상 확인용이다.
        return builder.withMemoryFactory(ByteArrayMemory::new).withStart(false).build();
    }

    private int copyIn(byte[] webp) {
        int ptr = allocate(webp.length);
        memory.write(ptr, webp);
        return ptr;
    }

    // 헤더가 WebP가 아니면 null. 호출한 쪽이 입력 버퍼를 해제한 뒤 예외를 던진다.
    private int[] readInfoOrNull(int in, int length) {
        long ok = getInfo.apply(in, length, infoScratch, infoScratch + Integer.BYTES)[0];
        if (ok == 0) {
            return null;
        }
        return new int[] {memory.readInt(infoScratch), memory.readInt(infoScratch + Integer.BYTES)};
    }

    // wasm32 포인터는 i32다. 모듈 최대 메모리가 1 GiB라 유효한 포인터는 항상 양수 int 범위다.
    private int allocate(int size) {
        int ptr = (int) alloc.apply(size)[0];
        if (ptr == 0) {
            throw outOfWasmMemory(size);
        }
        return ptr;
    }

    private IllegalStateException outOfWasmMemory(int size) {
        return new IllegalStateException(
                "wasm malloc failed: size=" + size + ", linearMemoryBytes=" + linearMemoryBytes());
    }

    // 원본 libwebp.wasm은 RUNTIME_COMPILED/INTERPRETER에서만 필요하다. 지연 초기화 홀더로 빌드타임 경로에서는 읽지 않는다.
    private static final class FullModule {
        static final WasmModule MODULE = load();

        private static WasmModule load() {
            try (InputStream in = WasmWebPDecoder.class.getResourceAsStream("libwebp.wasm")) {
                if (in == null) {
                    throw new UncheckedIOException(
                            new IOException("classpath resource not found: libwebp.wasm"));
                }
                return Parser.parse(in);
            } catch (IOException e) {
                throw new UncheckedIOException("failed to read libwebp.wasm", e);
            }
        }
    }

    // 런타임 컴파일은 비싸므로(모듈 전체) 한 번만 하고 결과 factory를 모든 인스턴스가 공유한다.
    // 이 홀더가 초기화될 때만 compiler/ASM 클래스가 로드된다.
    private static final class RuntimeCompiled {
        static final Function<Instance, Machine> FACTORY = MachineFactoryCompiler.compile(FullModule.MODULE);
    }
}
