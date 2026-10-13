package id.fatarc.portfolio.payflowreliability.ledger.dto;

import java.math.BigDecimal;

/**
 * @param balance debit-positive ledger balance
 * @param owedToMerchant for merchant accounts: what we owe the merchant (the negated balance); negative means the merchant owes us
 */
public record BalanceView(String account, String merchantId, String currency, BigDecimal balance, BigDecimal owedToMerchant) {
}
