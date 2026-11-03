package net.rivergod.webp.sidecar;

import java.util.function.Function;

/**
 * 해석이 끝난 클라이언트 설정. 항목마다 코드로 준 설정({@link WebPClientConfig}) → 시스템 프로퍼티 → 환경변수 →
 * 기본값 순서로 처음 정해진 값을 쓴다(docs/design/grpc-api.md §5, ADR-0020).
 *
 * @param endpoint gRPC target. {@code unix:///절대/경로}(UDS) 또는 {@code host:port}(TCP)
 * @param deadlineMs 호출 하나의 데드라인(혼잡 재시도 포함). 단위: ms
 * @param maxPixels 클라이언트 쪽 픽셀 수 상한(width × height). 0이면 서버 상한만 적용한다
 * @param busyRetries 혼잡("server busy") 재시도 횟수. 0이면 다시 시도하지 않는다
 */
record ClientConfig(String endpoint, long deadlineMs, long maxPixels, int busyRetries) {

    /** 기본 엔드포인트(ADR-0003). */
    static final String DEFAULT_ENDPOINT = "unix:///run/webp-sidecar/webp.sock";

    /** 기본 데드라인. 단위: ms. */
    static final long DEFAULT_DEADLINE_MS = 30_000;

    /**
     * 설정을 해석한다. 코드로 준 설정이 없다(시스템 프로퍼티 → 환경변수 → 기본값).
     *
     * @param sysprops 시스템 프로퍼티 조회 함수(없으면 null 반환)
     * @param env 환경변수 조회 함수(없으면 null 반환)
     * @throws IllegalArgumentException 숫자 형식이 틀리거나 범위를 벗어나면. 메시지에 키 이름을 담는다
     */
    static ClientConfig resolve(Function<String, String> sysprops, Function<String, String> env) {
        return resolve(WebPClientConfig.EMPTY, sysprops, env);
    }

    /**
     * 설정을 해석한다. 코드로 준 항목을 먼저 쓴다(빌더가 이미 범위를 검사했다).
     *
     * @param code 코드로 준 설정. 비운 항목은 시스템 프로퍼티 → 환경변수 → 기본값으로 채운다
     * @param sysprops 시스템 프로퍼티 조회 함수(없으면 null 반환)
     * @param env 환경변수 조회 함수(없으면 null 반환)
     * @throws IllegalArgumentException 시스템 프로퍼티·환경변수 값의 형식이 틀리거나 범위를 벗어나면
     */
    static ClientConfig resolve(WebPClientConfig code, Function<String, String> sysprops, Function<String, String> env) {
        String endpoint = code.endpoint().orElseGet(
                () -> lookup(sysprops, env, "webp.sidecar.endpoint", "WEBP_SIDECAR_ENDPOINT", DEFAULT_ENDPOINT));
        long deadline = code.deadlineMs().isPresent() ? code.deadlineMs().getAsLong()
                : number(sysprops, env, "webp.sidecar.deadline-ms", "WEBP_SIDECAR_DEADLINE_MS", DEFAULT_DEADLINE_MS);
        long maxPixels = code.maxPixels().isPresent() ? code.maxPixels().getAsLong()
                : number(sysprops, env, "webp.sidecar.max-pixels", "WEBP_SIDECAR_MAX_PIXELS", 0);
        long busyRetries = code.busyRetries().isPresent() ? code.busyRetries().getAsInt()
                : number(sysprops, env, "webp.sidecar.busy-retries", "WEBP_SIDECAR_BUSY_RETRIES", 0);
        if (endpoint.isBlank()) {
            throw new IllegalArgumentException("webp.sidecar.endpoint must not be empty");
        }
        if (deadline <= 0) {
            throw new IllegalArgumentException("webp.sidecar.deadline-ms must be > 0, got " + deadline);
        }
        if (maxPixels < 0) {
            throw new IllegalArgumentException("webp.sidecar.max-pixels must be >= 0, got " + maxPixels);
        }
        if (busyRetries < 0 || busyRetries > WebPClientConfig.MAX_BUSY_RETRIES) {
            throw new IllegalArgumentException("webp.sidecar.busy-retries must be 0.." + WebPClientConfig.MAX_BUSY_RETRIES
                    + ", got " + busyRetries);
        }
        return new ClientConfig(endpoint.trim(), deadline, maxPixels, (int) busyRetries);
    }

    /** 현재 프로세스의 시스템 프로퍼티와 환경변수로 해석한다. */
    static ClientConfig fromEnvironment() {
        return of(WebPClientConfig.EMPTY);
    }

    /** 코드로 준 설정을 먼저 쓰고, 나머지는 현재 프로세스의 시스템 프로퍼티와 환경변수로 해석한다. */
    static ClientConfig of(WebPClientConfig code) {
        return resolve(code, System::getProperty, System::getenv);
    }

    /**
     * 호출 하나의 값(ADR-0020 {@link WebPReadParam})으로 바꾼 설정. 0인 인자는 지금 값을 둔다.
     *
     * @param callDeadlineMs 이 호출의 데드라인. 단위: ms
     * @param callMaxPixels 이 호출의 클라이언트 픽셀 상한
     */
    ClientConfig forCall(long callDeadlineMs, long callMaxPixels) {
        return new ClientConfig(endpoint, callDeadlineMs > 0 ? callDeadlineMs : deadlineMs,
                callMaxPixels > 0 ? callMaxPixels : maxPixels, busyRetries);
    }

    /** 데드라인만 바꾼 설정(재시도 때 남은 시간). */
    ClientConfig withDeadlineMs(long newDeadlineMs) {
        return new ClientConfig(endpoint, newDeadlineMs, maxPixels, busyRetries);
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
