package net.rivergod.webp.imageio;

import io.grpc.Status;
import javax.imageio.IIOException;

/**
 * gRPC 오류를 {@link IIOException}으로 바꾼다(docs/design/grpc-api.md §4). 서버 불가와 입력 문제를 메시지만 보고도
 * 구분할 수 있게 하고, 원래 예외를 항상 cause로 넘긴다.
 */
final class GrpcErrors {

    /** ADR-0017 대기 상한 거부 메시지의 머리. */
    private static final String SERVER_BUSY = "server busy";

    private GrpcErrors() {}

    /**
     * ADR-0017 대기 상한 거부({@code UNAVAILABLE} "server busy")인지. 서버가 일을 시작하기 전의 거부라 같은 요청을 다시
     * 보내도 안전하다(ADR-0020 혼잡 재시도). cause 사슬도 본다.
     */
    static boolean isServerBusy(Throwable error) {
        Status status = Status.fromThrowable(error);
        return status.getCode() == Status.Code.UNAVAILABLE
                && status.getDescription() != null
                && status.getDescription().startsWith(SERVER_BUSY);
    }

    /**
     * 실패한 호출을 IIOException으로 바꾼다.
     *
     * @param error 호출이 던진 예외(보통 {@code StatusRuntimeException})
     * @param rpc RPC 이름(메시지용)
     * @param config 엔드포인트와 데드라인(메시지용)
     */
    static IIOException toIIOException(Throwable error, String rpc, ClientConfig config) {
        if (error instanceof IIOException iio) {
            return iio;
        }
        Status status = Status.fromThrowable(error);
        String detail = status.getDescription() == null ? "" : ": " + status.getDescription();
        String description = status.getDescription() == null ? "" : status.getDescription();
        String message = switch (status.getCode()) {
            // ADR-0017: 서버 대기 상한을 넘은 거부는 접속 불가와 구분한다(잠시 뒤 다시 시도할 수 있다).
            case UNAVAILABLE -> description.startsWith(SERVER_BUSY)
                    ? "WebP server busy (" + config.endpoint() + ")" + detail
                    : "WebP server unavailable (" + config.endpoint() + ")" + detail;
            // ADR-0017: 서버 쪽 요청 시간 상한과 클라이언트 데드라인을 구분한다.
            case DEADLINE_EXCEEDED -> description.contains("max_request_ms")
                    ? "WebP server request time limit exceeded (" + rpc + ")" + detail
                    : "WebP server timed out after " + config.deadlineMs() + " ms (" + rpc + ")";
            case INVALID_ARGUMENT -> "Invalid or corrupted WebP data" + detail;
            case RESOURCE_EXHAUSTED -> "WebP image exceeds server limit" + detail;
            case OUT_OF_RANGE -> "WebP frame index out of range" + detail;
            case CANCELLED -> "WebP request cancelled (" + rpc + ")" + detail;
            default -> "WebP server error " + status.getCode() + " (" + rpc + ")" + detail;
        };
        return new IIOException(message, error);
    }
}
