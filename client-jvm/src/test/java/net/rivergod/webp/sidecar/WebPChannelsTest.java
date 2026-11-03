package net.rivergod.webp.sidecar;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class WebPChannelsTest {

    @Test
    void unixPath_tripleSlashForm_returnsAbsolutePath() {
        // when / then
        assertEquals(Path.of("/run/webp-sidecar/webp.sock"), WebPChannels.unixPath("unix:///run/webp-sidecar/webp.sock"));
    }

    @Test
    void unixPath_singleColonForm_returnsPathAsGiven() {
        // when / then
        assertEquals(Path.of("relative/webp.sock"), WebPChannels.unixPath("unix:relative/webp.sock"));
    }

    @Test
    void unixPath_noPath_isRejected() {
        // when / then
        assertThrows(IllegalArgumentException.class, () -> WebPChannels.unixPath("unix://"));
    }
}
