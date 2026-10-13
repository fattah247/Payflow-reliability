package id.fatarc.portfolio.payflowreliability.reconciliation.dto;

import id.fatarc.portfolio.payflowreliability.common.MonetaryAmount;
import id.fatarc.portfolio.payflowreliability.common.ValidMoney;
import id.fatarc.portfolio.payflowreliability.payment.ProviderStatus;
import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * One import is one transaction; the row cap keeps it bounded. Split larger reports.
 *
 * @param settlementDate optional scope: when set, the report is taken as the provider's complete list for
 *                       that day, and payouts we recorded for that day but missing from it are flagged
 * @param merchantId     optional narrower scope, for providers that report per merchant account: only that
 *                       merchant's payouts are expected in the report. Requires settlementDate.
 */
public record ReconciliationReportRequest(
        LocalDate settlementDate,
        @Size(max = 100) String merchantId,
        @NotNull @Size(max = 1000) List<@Valid @NotNull Row> rows
) {
    public ReconciliationReportRequest(List<Row> rows) {
        this(null, null, rows);
    }

    public ReconciliationReportRequest(LocalDate settlementDate, List<Row> rows) {
        this(settlementDate, null, rows);
    }

    @AssertTrue(message = "rows must not be empty unless settlementDate is given")
    public boolean isScoped() {
        return rows == null || !rows.isEmpty() || settlementDate != null;
    }

    @AssertTrue(message = "merchantId narrows a dated report; give settlementDate too")
    public boolean isMerchantScopeDated() {
        return merchantId == null || settlementDate != null;
    }

    /**
     * @param refundedAmount optional: what the provider says was refunded on this transaction
     */
    @ValidMoney
    public record Row(
            @NotBlank @Size(max = 100) String providerTransactionId,
            @NotNull BigDecimal amount,
            @NotBlank @Pattern(regexp = "[A-Z]{3}", message = "must be a 3-letter uppercase ISO currency code") String currency,
            @NotNull ProviderStatus providerStatus,
            LocalDate settlementDate,
            @PositiveOrZero BigDecimal refundedAmount
    ) implements MonetaryAmount {
        public Row(String providerTransactionId, BigDecimal amount, String currency, ProviderStatus providerStatus,
                   LocalDate settlementDate) {
            this(providerTransactionId, amount, currency, providerStatus, settlementDate, null);
        }
    }
}
