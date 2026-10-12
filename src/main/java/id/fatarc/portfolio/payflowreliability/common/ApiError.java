package id.fatarc.portfolio.payflowreliability.common;

import java.time.Instant;

/**
 * Error body for every non-2xx response.
 *
 * @param code      stable machine-readable error code
 * @param retryable whether sending the exact same request again can succeed
 * @param requestId correlation id, also returned in the {@code X-Request-Id} header and stored on audit events
 */
public record ApiError(
        Instant timestamp,
        int status,
        String error,
        String code,
        String message,
        boolean retryable,
        String requestId
) {
    public static ApiError of(ErrorCode code, String message) {
        return new ApiError(
                Instant.now(),
                code.status().value(),
                code.status().getReasonPhrase(),
                code.name(),
                message,
                code.retryable(),
                RequestId.current()
        );
    }
}
