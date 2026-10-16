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

    UNAUTHENTICATED(HttpStatus.UNAUTHORIZED, false),
    INVALID_SIGNATURE(HttpStatus.UNAUTHORIZED, false),

    FORBIDDEN(HttpStatus.FORBIDDEN, false),
    /** A money-moving decision must be approved by a different operator than the one who proposed it. */
    FOUR_EYES_REQUIRED(HttpStatus.FORBIDDEN, false),

    PAYMENT_INTENT_NOT_FOUND(HttpStatus.NOT_FOUND, false),
    REFUND_NOT_FOUND(HttpStatus.NOT_FOUND, false),
    REVIEW_CASE_NOT_FOUND(HttpStatus.NOT_FOUND, false),
    SETTLEMENT_BATCH_NOT_FOUND(HttpStatus.NOT_FOUND, false),
    MERCHANT_NOT_FOUND(HttpStatus.NOT_FOUND, false),
    OUTBOX_COMMAND_NOT_FOUND(HttpStatus.NOT_FOUND, false),
    ROUTE_NOT_FOUND(HttpStatus.NOT_FOUND, false),
    METHOD_NOT_ALLOWED(HttpStatus.METHOD_NOT_ALLOWED, false),
    UNSUPPORTED_MEDIA_TYPE(HttpStatus.UNSUPPORTED_MEDIA_TYPE, false),

    INVALID_STATE_TRANSITION(HttpStatus.CONFLICT, false),
    CONCURRENT_MODIFICATION(HttpStatus.CONFLICT, true),
    PAYMENT_ON_HOLD(HttpStatus.CONFLICT, true),
    NOTHING_TO_SETTLE(HttpStatus.CONFLICT, false),
    REVIEW_CASE_ALREADY_RESOLVED(HttpStatus.CONFLICT, false),
    REFUND_NOT_CANCELLABLE(HttpStatus.CONFLICT, false),
    MERCHANT_ALREADY_EXISTS(HttpStatus.CONFLICT, false),
    /** The same Idempotency-Key is still being processed by another request. */
    IDEMPOTENCY_KEY_IN_PROGRESS(HttpStatus.CONFLICT, true),
    OUTBOX_COMMAND_NOT_RETRYABLE(HttpStatus.CONFLICT, false),
    /** The payment waits for the customer's step-up confirmation before it can start. */
    STEP_UP_REQUIRED(HttpStatus.CONFLICT, false),

    IDEMPOTENCY_KEY_REUSED(HttpStatus.UNPROCESSABLE_ENTITY, false),
    REFUND_AMOUNT_NOT_ALLOWED(HttpStatus.UNPROCESSABLE_ENTITY, false),
    SETTLEMENT_CURRENCY_REQUIRED(HttpStatus.UNPROCESSABLE_ENTITY, false),
    RESOLUTION_ACTION_NOT_APPLICABLE(HttpStatus.UNPROCESSABLE_ENTITY, false),
    MERCHANT_INACTIVE(HttpStatus.UNPROCESSABLE_ENTITY, false),
    /** The device-risk policy blocked this payment; it will never be handed to the provider. */
    PAYMENT_BLOCKED(HttpStatus.UNPROCESSABLE_ENTITY, false),
    /** A step-up confirmed with another method than the one the decision required. */
    CONFIRMATION_METHOD_MISMATCH(HttpStatus.UNPROCESSABLE_ENTITY, false),
    /** A bank statement file that is not well-formed, not camt.053, or does not add up. */
    BANK_STATEMENT_INVALID(HttpStatus.UNPROCESSABLE_ENTITY, false),
    /** A statement id or sequence number already imported, with different content. */
    BANK_STATEMENT_CONFLICT(HttpStatus.CONFLICT, false),
    BREAK_NOT_FOUND(HttpStatus.NOT_FOUND, false),
    BREAK_ACTION_NOT_APPLICABLE(HttpStatus.UNPROCESSABLE_ENTITY, false),

    PROVIDER_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, true),

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
