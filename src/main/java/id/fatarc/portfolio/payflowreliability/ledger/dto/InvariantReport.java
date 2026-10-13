package id.fatarc.portfolio.payflowreliability.ledger.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * @param violations empty when the books are consistent
 */
public record InvariantReport(Instant checkedAt, int journalsChecked, int merchantBalancesChecked,
                              List<Violation> violations) {
    @JsonProperty("healthy")
    public boolean healthy() {
        return violations.isEmpty();
    }

    /**
     * @param kind UNBALANCED_JOURNAL (postings do not sum to zero) or MERCHANT_BALANCE_MISMATCH
     *             (ledger balance differs from what payments, refunds, reversals and payouts imply)
     */
    public record Violation(String kind, String merchantId, String currency, BigDecimal expected,
                            BigDecimal actual, String detail) {
    }
}
