package net.rivergod.webp.sidecar;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import java.util.function.Function;
import org.junit.jupiter.api.Test;

class ClientConfigTest {

    private static Function<String, String> of(Map<String, String> map) {
        return map::get;
    }

    @Test
    void resolve_nothingSet_usesDefaults() {
        // when
        ClientConfig c = ClientConfig.resolve(of(Map.of()), of(Map.of()));

        // then
        assertEquals(new ClientConfig("unix:///run/webp-sidecar/webp.sock", 30_000, 0, 0), c);
    }

    @Test
    void resolve_envSet_overridesDefault() {
        // when
        ClientConfig c = ClientConfig.resolve(of(Map.of()), of(Map.of("WEBP_SIDECAR_ENDPOINT", "localhost:50051")));

        // then
        assertEquals("localhost:50051", c.endpoint());
    }

    @Test
    void resolve_syspropAndEnvSet_syspropWins() {
        // when
        ClientConfig c = ClientConfig.resolve(of(Map.of("webp.sidecar.deadline-ms", "100")),
                of(Map.of("WEBP_SIDECAR_DEADLINE_MS", "200")));

        // then
        assertEquals(100, c.deadlineMs());
    }

    @Test
    void resolve_nonNumericValue_namesTheKey() {
        // when
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> ClientConfig.resolve(of(Map.of("webp.sidecar.max-pixels", "lots")), of(Map.of())));

        // then
        assertTrue(e.getMessage().contains("webp.sidecar.max-pixels"), e.getMessage());
    }

    @Test
    void resolve_zeroDeadline_isRejected() {
        // when / then
        assertThrows(IllegalArgumentException.class,
                () -> ClientConfig.resolve(of(Map.of("webp.sidecar.deadline-ms", "0")), of(Map.of())));
    }

    @Test
    void resolve_codeConfigSet_winsOverSyspropAndEnv() {
        // given
        WebPClientConfig code = WebPClientConfig.builder().endpoint("code:1").deadlineMs(10).build();

        // when
        ClientConfig c = ClientConfig.resolve(code,
                of(Map.of("webp.sidecar.endpoint", "prop:1", "webp.sidecar.deadline-ms", "20")),
                of(Map.of("WEBP_SIDECAR_ENDPOINT", "env:1")));

        // then
        assertEquals("code:1", c.endpoint());
        assertEquals(10, c.deadlineMs());
    }

    @Test
    void resolve_codeConfigItemUnset_fallsThroughToSysprop() {
        // given: 엔드포인트만 코드로 정한다
        WebPClientConfig code = WebPClientConfig.builder().endpoint("code:1").build();

        // when
        ClientConfig c = ClientConfig.resolve(code, of(Map.of("webp.sidecar.max-pixels", "99")),
                of(Map.of("WEBP_SIDECAR_BUSY_RETRIES", "3")));

        // then: 픽셀 상한은 프로퍼티, 재시도는 환경변수, 데드라인은 기본값
        assertEquals(new ClientConfig("code:1", 30_000, 99, 3), c);
    }

    @Test
    void resolve_busyRetriesOutOfRange_namesTheKey() {
        // when
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> ClientConfig.resolve(of(Map.of("webp.sidecar.busy-retries", "11")), of(Map.of())));

        // then
        assertTrue(e.getMessage().contains("webp.sidecar.busy-retries"), e.getMessage());
    }

    @Test
    void forCall_zeroArguments_keepConfiguredValues() {
        // given
        ClientConfig c = new ClientConfig("a:1", 1_000, 50, 2);

        // when
        ClientConfig call = c.forCall(0, 0);

        // then
        assertEquals(c, call);
    }

    @Test
    void forCall_positiveArguments_replaceDeadlineAndMaxPixelsOnly() {
        // given
        ClientConfig c = new ClientConfig("a:1", 1_000, 50, 2);

        // when
        ClientConfig call = c.forCall(200, 7);

        // then
        assertEquals(new ClientConfig("a:1", 200, 7, 2), call);
    }
}
