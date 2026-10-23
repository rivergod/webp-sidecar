package net.rivergod.webp.imageio;

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
        assertEquals(new ClientConfig("unix:///run/webp-imageio/webp.sock", 30_000, 0), c);
    }

    @Test
    void resolve_envSet_overridesDefault() {
        // when
        ClientConfig c = ClientConfig.resolve(of(Map.of()), of(Map.of("WEBP_IMAGEIO_ENDPOINT", "localhost:50051")));

        // then
        assertEquals("localhost:50051", c.endpoint());
    }

    @Test
    void resolve_syspropAndEnvSet_syspropWins() {
        // when
        ClientConfig c = ClientConfig.resolve(of(Map.of("webp.imageio.deadline-ms", "100")),
                of(Map.of("WEBP_IMAGEIO_DEADLINE_MS", "200")));

        // then
        assertEquals(100, c.deadlineMs());
    }

    @Test
    void resolve_nonNumericValue_namesTheKey() {
        // when
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> ClientConfig.resolve(of(Map.of("webp.imageio.max-pixels", "lots")), of(Map.of())));

        // then
        assertTrue(e.getMessage().contains("webp.imageio.max-pixels"), e.getMessage());
    }

    @Test
    void resolve_zeroDeadline_isRejected() {
        // when / then
        assertThrows(IllegalArgumentException.class,
                () -> ClientConfig.resolve(of(Map.of("webp.imageio.deadline-ms", "0")), of(Map.of())));
    }
}
