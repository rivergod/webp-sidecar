package net.rivergod.webp.imageio;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import javax.imageio.IIOException;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.api.Test;

class GrpcErrorsTest {

    private static final ClientConfig CONFIG = new ClientConfig("unix:///run/x.sock", 1234, 0);

    @ParameterizedTest
    @CsvSource({
        "UNAVAILABLE, WebP server unavailable (unix:///run/x.sock)",
        "DEADLINE_EXCEEDED, WebP server timed out after 1234 ms",
        "INVALID_ARGUMENT, Invalid or corrupted WebP data",
        "RESOURCE_EXHAUSTED, WebP image exceeds server limit",
        "INTERNAL, WebP server error INTERNAL",
    })
    void toIIOException_statusCode_messageNamesTheCategory(Status.Code code, String expectedPrefix) {
        // given
        StatusRuntimeException error = code.toStatus().withDescription("detail").asRuntimeException();

        // when
        IIOException e = GrpcErrors.toIIOException(error, "Decode", CONFIG);

        // then
        assertTrue(e.getMessage().startsWith(expectedPrefix), e.getMessage());
    }

    @Test
    void toIIOException_serverBusy_isDistinguishedFromUnavailable() {
        // given: 서버 대기 상한(ADR-0017) 거부
        StatusRuntimeException busy = Status.UNAVAILABLE
                .withDescription("server busy: 8 requests queued (max_queued_requests 8); retry later")
                .asRuntimeException();

        // when
        IIOException e = GrpcErrors.toIIOException(busy, "Decode", CONFIG);

        // then
        assertTrue(e.getMessage().startsWith("WebP server busy"), e.getMessage());
    }

    @Test
    void toIIOException_serverRequestTimeLimit_isDistinguishedFromClientDeadline() {
        // given: 서버 쪽 요청 시간 상한(ADR-0017)
        StatusRuntimeException limit = Status.DEADLINE_EXCEEDED
                .withDescription("request exceeded max_request_ms on the server")
                .asRuntimeException();

        // when
        IIOException e = GrpcErrors.toIIOException(limit, "Decode", CONFIG);

        // then
        assertTrue(e.getMessage().startsWith("WebP server request time limit exceeded"), e.getMessage());
    }

    @Test
    void toIIOException_anyStatus_keepsOriginalAsCause() {
        // given
        StatusRuntimeException error = Status.UNAVAILABLE.asRuntimeException();

        // when
        IIOException e = GrpcErrors.toIIOException(error, "Decode", CONFIG);

        // then
        assertSame(error, e.getCause());
    }

    @Test
    void toIIOException_alreadyIioException_returnsItUnchanged() {
        // given
        IIOException original = new IIOException("client-side check failed");

        // when
        IIOException e = GrpcErrors.toIIOException(original, "Decode", CONFIG);

        // then
        assertSame(original, e);
    }
}
