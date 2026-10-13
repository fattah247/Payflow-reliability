package id.fatarc.portfolio.payflowreliability.common;

import java.math.BigDecimal;

/** A request that carries an amount in a currency; validated together by {@link ValidMoney}. */
public interface MonetaryAmount {
    BigDecimal amount();

    String currency();
}
