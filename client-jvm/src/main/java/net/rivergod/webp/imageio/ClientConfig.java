package net.rivergod.webp.imageio;

import java.util.function.Function;

/**
 * 클라이언트 설정. SPI는 인자 없는 생성자로 전역 등록되므로 설정을 주입할 수 없다. 각 값은
 * 시스템 프로퍼티 → 환경변수 → 기본값 순서로 해석한다(docs/design/grpc-api.md §5).
 *
 * @param endpoint gRPC target. {@code unix:///절대/경로}(UDS) 또는 {@code host:port}(TCP)
 * @param deadlineMs 호출 하나의 데드라인. 단위: ms
 * @param maxPixels 클라이언트 쪽 픽셀 수 상한(width × height). 0이면 서버 상한만 적용한다
 */
record ClientConfig(String endpoint, long deadlineMs, long maxPixels) {

    /** 기본 엔드포인트(ADR-0003). */
    static final String DEFAULT_ENDPOINT = "unix:///run/webp-imageio/webp.sock";

    /** 기본 데드라인. 단위: ms. */
    static final long DEFAULT_DEADLINE_MS = 30_000;

    /**
     * 설정을 해석한다.
     *
     * @param sysprops 시스템 프로퍼티 조회 함수(없으면 null 반환)
     * @param env 환경변수 조회 함수(없으면 null 반환)
     * @throws IllegalArgumentException 숫자 형식이 틀리거나 범위를 벗어나면. 메시지에 키 이름을 담는다
     */
    static ClientConfig resolve(Function<String, String> sysprops, Function<String, String> env) {
        String endpoint = lookup(sysprops, env, "webp.imageio.endpoint", "WEBP_IMAGEIO_ENDPOINT", DEFAULT_ENDPOINT);
        long deadline = number(sysprops, env, "webp.imageio.deadline-ms", "WEBP_IMAGEIO_DEADLINE_MS", DEFAULT_DEADLINE_MS);
        long maxPixels = number(sysprops, env, "webp.imageio.max-pixels", "WEBP_IMAGEIO_MAX_PIXELS", 0);
        if (endpoint.isBlank()) {
            throw new IllegalArgumentException("webp.imageio.endpoint must not be empty");
        }
        if (deadline <= 0) {
            throw new IllegalArgumentException("webp.imageio.deadline-ms must be > 0, got " + deadline);
        }
        if (maxPixels < 0) {
            throw new IllegalArgumentException("webp.imageio.max-pixels must be >= 0, got " + maxPixels);
        }
        return new ClientConfig(endpoint.trim(), deadline, maxPixels);
    }

    /** 현재 프로세스의 시스템 프로퍼티와 환경변수로 해석한다. */
    static ClientConfig fromEnvironment() {
        return resolve(System::getProperty, System::getenv);
    }

    private static String lookup(
            Function<String, String> sysprops, Function<String, String> env, String prop, String var, String def) {
        String v = sysprops.apply(prop);
        if (v == null) {
            v = env.apply(var);
        }
        return v == null ? def : v;
    }

    private static long number(
            Function<String, String> sysprops, Function<String, String> env, String prop, String var, long def) {
        String raw = lookup(sysprops, env, prop, var, null);
        if (raw == null) {
            return def;
        }
        try {
            return Long.parseLong(raw.trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(prop + " / " + var + ": not a number: " + raw, e);
        }
    }
}
