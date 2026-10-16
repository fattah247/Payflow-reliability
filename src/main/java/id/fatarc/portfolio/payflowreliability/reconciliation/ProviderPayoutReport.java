package id.fatarc.portfolio.payflowreliability.reconciliation;

import id.fatarc.portfolio.payflowreliability.common.Money;
import jakarta.persistence.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/** The provider's settlement report row for one payout: the provider's leg of the three-way match. */
@Entity
@Table(name = "provider_payout_reports")
public class ProviderPayoutReport {
    @Id
    private UUID id;

    @Column(nullable = false)
    private UUID settlementBatchId;

    @Column(nullable = false)
    private LocalDate reportDate;

    @Column(nullable = false)
    private String providerPayoutId;

    @Column(nullable = false, precision = 19, scale = 4)
    private BigDecimal grossAmount;

    @Column(nullable = false, precision = 19, scale = 4)
    private BigDecimal feeAmount;

    @Column(nullable = false, precision = 19, scale = 4)
    private BigDecimal netAmount;

    @Column(nullable = false, length = 3)
    private String currency;

    private LocalDate valueDate;

    /** Whether the row agreed with the ledger and with what the provider said when it accepted the payout. */
    @Column(nullable = false)
    private boolean consistent;

    @Column(nullable = false)
    private String importedBy;

    @Column(nullable = false)
    private Instant importedAt;

    protected ProviderPayoutReport() {
    }

    ProviderPayoutReport(UUID settlementBatchId, LocalDate reportDate, String providerPayoutId, BigDecimal grossAmount,
                         BigDecimal feeAmount, BigDecimal netAmount, String currency, LocalDate valueDate, boolean consistent,
                         String importedBy, Instant now) {
        this.id = UUID.randomUUID();
        this.settlementBatchId = settlementBatchId;
        this.reportDate = reportDate;
        this.providerPayoutId = providerPayoutId;
        this.grossAmount = grossAmount;
        this.feeAmount = feeAmount;
        this.netAmount = netAmount;
        this.currency = currency;
        this.valueDate = valueDate;
        this.consistent = consistent;
        this.importedBy = importedBy;
        this.importedAt = now;
    }

    boolean sameAs(String providerPayoutId, BigDecimal gross, BigDecimal fee, BigDecimal net) {
        return this.providerPayoutId.equals(providerPayoutId) && grossAmount.compareTo(gross) == 0
                && feeAmount.compareTo(fee) == 0 && netAmount.compareTo(net) == 0;
    }

    public UUID getId() { return id; }
    public UUID getSettlementBatchId() { return settlementBatchId; }
    public LocalDate getReportDate() { return reportDate; }
    public String getProviderPayoutId() { return providerPayoutId; }
    public BigDecimal getGrossAmount() { return Money.atScale(grossAmount, currency); }
    public BigDecimal getFeeAmount() { return Money.atScale(feeAmount, currency); }
    public BigDecimal getNetAmount() { return Money.atScale(netAmount, currency); }
    public String getCurrency() { return currency; }
    public LocalDate getValueDate() { return valueDate; }
    public boolean isConsistent() { return consistent; }
    public String getImportedBy() { return importedBy; }
    public Instant getImportedAt() { return importedAt; }
}
