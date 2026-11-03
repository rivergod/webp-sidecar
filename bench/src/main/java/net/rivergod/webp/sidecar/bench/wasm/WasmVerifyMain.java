package net.rivergod.webp.sidecar.bench.wasm;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Locale;

/**
 * {@link WasmWebPDecoder}가 동작하는지 Gradle 없이 확인하는 명령줄 도구다(벤치마크 전용, JMH 아님).
 *
 * <p>사용법: {@code WasmVerifyMain <MODE> <warmup> <iterations> <file.webp>[=<ref.pam>] ...}
 *
 * <ul>
 *   <li>{@code MODE}: {@link WasmWebPDecoder.Mode} 이름
 *   <li>{@code ref.pam}: 기준 출력({@code dwebp -pam}). 주면 RGBA 바이트를 비교해 일치 여부, 다른 바이트 수, 최대 차이,
 *       PSNR(dB)을 출력한다.
 *   <li>{@code warmup}, {@code iterations}: 0보다 크면 단일 스레드로 그만큼 반복해 1회 디코딩 시간(ms)의 평균과 최솟값을
 *       출력한다. 대략적인 값이다(JMH가 아니다).
 * </ul>
 *
 * <p>종료 코드: 모든 비교가 정확히 일치하면 0, 하나라도 다르면 1, 사용법 오류는 2.
 */
public final class WasmVerifyMain {

    private WasmVerifyMain() {}

    /**
     * 명령줄 진입점.
     *
     * @param args 위 사용법 참고
     * @throws IOException 입력 파일을 읽지 못했을 때
     */
    public static void main(String[] args) throws IOException {
        if (args.length < 4) {
            System.err.println(
                    "usage: WasmVerifyMain <MODE> <warmup> <iterations> <file.webp>[=<ref.pam>] ...");
            System.exit(2);
        }
        WasmWebPDecoder.Mode mode = WasmWebPDecoder.Mode.valueOf(args[0]);
        int warmup = Integer.parseInt(args[1]);
        int iterations = Integer.parseInt(args[2]);

        // 인스턴스 생성 시간에는 모듈 로드(와 RUNTIME_COMPILED의 컴파일)가 들어간다.
        long t0 = System.nanoTime();
        WasmWebPDecoder decoder = new WasmWebPDecoder(mode);
        double createMs = (System.nanoTime() - t0) / 1e6;
        System.out.printf(
                Locale.ROOT,
                "mode=%s libwebp=0x%06x createMs=%.1f java=%s%n",
                mode,
                decoder.decoderVersion(),
                createMs,
                System.getProperty("java.version"));

        boolean allMatch = true;
        for (int i = 3; i < args.length; i++) {
            String[] parts = args[i].split("=", 2);
            Path webpPath = Path.of(parts[0]);
            byte[] webp = Files.readAllBytes(webpPath);

            int[] size = decoder.info(webp);
            byte[] rgba = decoder.decodeRgba(webp);
            StringBuilder line = new StringBuilder();
            line.append(String.format(
                    Locale.ROOT,
                    "%s %dx%d in=%dB out=%dB linearMemory=%dMiB",
                    webpPath.getFileName(),
                    size[0],
                    size[1],
                    webp.length,
                    rgba.length,
                    decoder.linearMemoryBytes() >> 20));

            // 기준 PAM과 비교한다. lossless는 정확 일치가 기대값이고, lossy는 차이를 숫자로 보고한다.
            if (parts.length == 2) {
                byte[] ref = readPamPixels(Path.of(parts[1]));
                line.append(' ').append(compare(rgba, ref));
                allMatch &= Arrays.equals(rgba, ref);
            }

            // 단일 스레드 반복 측정. JIT 워밍업 후 평균과 최솟값만 낸다.
            if (iterations > 0) {
                for (int w = 0; w < warmup; w++) {
                    decoder.decodeRgba(webp);
                }
                long min = Long.MAX_VALUE;
                long total = 0;
                for (int n = 0; n < iterations; n++) {
                    long s = System.nanoTime();
                    decoder.decodeRgba(webp);
                    long d = System.nanoTime() - s;
                    total += d;
                    min = Math.min(min, d);
                }
                line.append(String.format(
                        Locale.ROOT,
                        " decodeMs(mean=%.2f min=%.2f warmup=%d n=%d)",
                        total / 1e6 / iterations,
                        min / 1e6,
                        warmup,
                        iterations));
            }
            System.out.println(line);
        }
        System.exit(allMatch ? 0 : 1);
    }

    // PAM(P7) 헤더를 건너뛰고 픽셀 바이트만 돌려준다. dwebp -pam 출력은 DEPTH 4(RGB_ALPHA), MAXVAL 255다.
    private static byte[] readPamPixels(Path pam) throws IOException {
        byte[] all = Files.readAllBytes(pam);
        byte[] marker = "ENDHDR\n".getBytes(StandardCharsets.US_ASCII);
        for (int i = 0; i + marker.length <= all.length; i++) {
            if (Arrays.equals(all, i, i + marker.length, marker, 0, marker.length)) {
                return Arrays.copyOfRange(all, i + marker.length, all.length);
            }
        }
        throw new IOException("not a PAM file: " + pam);
    }

    private static String compare(byte[] actual, byte[] expected) {
        if (actual.length != expected.length) {
            return "MISMATCH(length " + actual.length + " != " + expected.length + ")";
        }
        long differing = 0;
        int maxDiff = 0;
        double squaredError = 0;
        for (int i = 0; i < actual.length; i++) {
            int d = Math.abs((actual[i] & 0xFF) - (expected[i] & 0xFF));
            if (d != 0) {
                differing++;
                maxDiff = Math.max(maxDiff, d);
                squaredError += (double) d * d;
            }
        }
        if (differing == 0) {
            return "MATCH(exact)";
        }
        double mse = squaredError / actual.length;
        double psnr = 10 * Math.log10(255.0 * 255.0 / mse);
        return String.format(
                Locale.ROOT,
                "MISMATCH(differingBytes=%d/%d maxDiff=%d psnr=%.2fdB)",
                differing,
                actual.length,
                maxDiff,
                psnr);
    }
}
