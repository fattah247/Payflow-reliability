package id.fatarc.portfolio.payflowreliability.payment;

import id.fatarc.portfolio.payflowreliability.common.BusinessException;
import id.fatarc.portfolio.payflowreliability.common.ErrorCode;

import java.util.regex.Pattern;

/** Rules for the required Idempotency-Key on payment and refund creation. */
public final class IdempotencyKeys {
    private static final Pattern VALID = Pattern.compile("[\\x21-\\x7E]{1,100}");

    private IdempotencyKeys() {
    }

    public static void requireValid(String key) {
        if (key == null || key.isBlank()) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "Idempotency-Key header is required.");
        }
        if (!VALID.matcher(key).matches()) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                    "Idempotency-Key must be 1-100 printable ASCII characters without spaces.");
        }
    }
}
