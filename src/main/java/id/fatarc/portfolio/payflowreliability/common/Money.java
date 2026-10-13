package id.fatarc.portfolio.payflowreliability.common;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Currency;
import java.util.Optional;

/**
 * Currency-aware amount rules. Precision comes from ISO 4217 (via {@link Currency}): JPY has no
 * minor unit, IDR and USD have 2 decimals, KWD and BHD have 3. Amount columns are numeric(19, 4),
 * which fits every ISO currency.
 */
public final class Money {
    public static final int MAX_INTEGER_DIGITS = 15;

    private Money() {
    }

    /** A real ISO 4217 currency with a defined minor unit (not a fund or precious-metal code such as XAU). */
    public static boolean isSupportedCurrency(String code) {
        if (code == null || !code.matches("[A-Z]{3}")) {
            return false;
        }
        try {
            return Currency.getInstance(code).getDefaultFractionDigits() >= 0;
        } catch (IllegalArgumentException ex) {
            return false;
        }
    }

    public static int fractionDigits(String currency) {
        if (!isSupportedCurrency(currency)) {
            throw new IllegalArgumentException("Unsupported currency: " + currency);
        }
        return Currency.getInstance(currency).getDefaultFractionDigits();
    }

    /** Why the amount is not valid for the currency, or empty when it is. Assumes the currency is supported. */
    public static Optional<String> amountProblem(BigDecimal amount, String currency) {
        if (amount.signum() <= 0) {
            return Optional.of("must be greater than zero");
        }
        int digits = fractionDigits(currency);
        BigDecimal stripped = amount.stripTrailingZeros();
        if (Math.max(stripped.scale(), 0) > digits) {
            return Optional.of(digits == 0
                    ? currency + " has no minor unit; send a whole amount"
                    : currency + " allows at most " + digits + " decimal places");
        }
        if (stripped.precision() - stripped.scale() > MAX_INTEGER_DIGITS) {
            return Optional.of("must have at most " + MAX_INTEGER_DIGITS + " integer digits");
        }
        return Optional.empty();
    }

    /**
     * The amount at the currency's scale, so what a client sends, what is stored and what every
     * endpoint returns are the same value (JPY 1500, IDR 1500.00, KWD 1.500). Never rounds: excess
     * precision is a validation bug upstream and fails loudly instead of silently changing money.
     */
    public static BigDecimal normalize(BigDecimal amount, String currency) {
        return amount.setScale(fractionDigits(currency), RoundingMode.UNNECESSARY);
    }

    /**
     * A stored amount at its currency's scale. Columns hold 4 decimals, so values read back from the
     * database are rescaled here (never rounding: the stored value was validated when written).
     */
    public static BigDecimal atScale(BigDecimal amount, String currency) {
        if (amount == null || !isSupportedCurrency(currency)) {
            return amount;
        }
        return amount.setScale(fractionDigits(currency), RoundingMode.UNNECESSARY);
    }

    public static BigDecimal zero(String currency) {
        return BigDecimal.ZERO.setScale(fractionDigits(currency), RoundingMode.UNNECESSARY);
    }
}
