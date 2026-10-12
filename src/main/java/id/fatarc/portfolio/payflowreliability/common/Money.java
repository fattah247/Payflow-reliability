package id.fatarc.portfolio.payflowreliability.common;

import java.math.BigDecimal;
import java.math.RoundingMode;

public final class Money {
    /** Matches the numeric(19, 2) amount columns. Inputs are validated against it with @Digits. */
    public static final int SCALE = 2;

    private Money() {
    }

    /**
     * Normalizes an amount to the stored scale, so what a client sends, what is stored and what
     * every endpoint returns are the same value. Never rounds: an amount with more decimals is a
     * validation bug upstream and fails loudly instead of being silently changed.
     */
    public static BigDecimal normalize(BigDecimal amount) {
        return amount.setScale(SCALE, RoundingMode.UNNECESSARY);
    }
}
