package id.fatarc.portfolio.payflowreliability.common;

import org.springframework.http.HttpStatus;

/**
 * Machine-readable error codes. Each code fixes the HTTP status and whether repeating
 * the exact same request can succeed, so clients and the provider can decide to retry
 * without parsing messages.
 */
public enum ErrorCode {
    VALIDATION_FAILED(HttpStatus.BAD_REQUEST, false),
    MALFORMED_REQUEST(HttpStatus.BAD_REQUEST, false),

    INVALID_SIGNATURE(HttpStatus.UNAUTHORIZED, false),

    PAYMENT_INTENT_NOT_FOUND(HttpStatus.NOT_FOUND, false),
    REFUND_NOT_FOUND(HttpStatus.NOT_FOUND, false),
    REVIEW_CASE_NOT_FOUND(HttpStatus.NOT_FOUND, false),
    SETTLEMENT_BATCH_NOT_FOUND(HttpStatus.NOT_FOUND, false),
    ROUTE_NOT_FOUND(HttpStatus.NOT_FOUND, false),
    METHOD_NOT_ALLOWED(HttpStatus.METHOD_NOT_ALLOWED, false),
    UNSUPPORTED_MEDIA_TYPE(HttpStatus.UNSUPPORTED_MEDIA_TYPE, false),

    INVALID_STATE_TRANSITION(HttpStatus.CONFLICT, false),
    CONCURRENT_MODIFICATION(HttpStatus.CONFLICT, true),
    PAYMENT_ON_HOLD(HttpStatus.CONFLICT, true),
    NOTHING_TO_SETTLE(HttpStatus.CONFLICT, false),
    REVIEW_CASE_ALREADY_RESOLVED(HttpStatus.CONFLICT, false),
    REFUND_NOT_COMPLETABLE(HttpStatus.CONFLICT, false),

    IDEMPOTENCY_KEY_REUSED(HttpStatus.UNPROCESSABLE_ENTITY, false),
    REFUND_AMOUNT_NOT_ALLOWED(HttpStatus.UNPROCESSABLE_ENTITY, false),
    SETTLEMENT_CURRENCY_REQUIRED(HttpStatus.UNPROCESSABLE_ENTITY, false),
    RESOLUTION_ACTION_NOT_APPLICABLE(HttpStatus.UNPROCESSABLE_ENTITY, false),

    /** A constraint other than uniqueness failed: a deterministic bug, so retrying will not help. */
    DATA_INTEGRITY_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, false),
    INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, true);

    private final HttpStatus status;
    private final boolean retryable;

    ErrorCode(HttpStatus status, boolean retryable) {
        this.status = status;
        this.retryable = retryable;
    }

    public HttpStatus status() { return status; }
    public boolean retryable() { return retryable; }
}
