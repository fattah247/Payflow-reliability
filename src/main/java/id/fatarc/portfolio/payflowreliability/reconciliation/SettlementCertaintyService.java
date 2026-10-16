package id.fatarc.portfolio.payflowreliability.reconciliation;

import id.fatarc.portfolio.payflowreliability.bank.BankStatementLine;
import id.fatarc.portfolio.payflowreliability.bank.BankStatementLineRepository;
import id.fatarc.portfolio.payflowreliability.common.BusinessException;
import id.fatarc.portfolio.payflowreliability.common.ErrorCode;
import id.fatarc.portfolio.payflowreliability.ledger.JournalType;
import id.fatarc.portfolio.payflowreliability.ledger.LedgerJournal;
import id.fatarc.portfolio.payflowreliability.ledger.LedgerJournalRepository;
import id.fatarc.portfolio.payflowreliability.payment.*;
import id.fatarc.portfolio.payflowreliability.reconciliation.breaks.ReconciliationBreak;
import id.fatarc.portfolio.payflowreliability.reconciliation.breaks.ReconciliationBreakRepository;
import id.fatarc.portfolio.payflowreliability.settlement.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Answers "what do we know about this money, and what proves it?" for a payout batch (its three legs side by
 * side, with every check spelled out) and for a payment (each stage from authorized to reconciled, with the
 * evidence behind it).
 */
@Service
public class SettlementCertaintyService {
    private final SettlementBatchRepository batchRepository;
    private final SettlementBatchItemRepository itemRepository;
    private final ProviderPayoutReportRepository reportRepository;
    private final BankStatementLineRepository lineRepository;
    private final LedgerJournalRepository journalRepository;
    private final ReconciliationBreakRepository breakRepository;
    private final ProviderEventRepository providerEventRepository;
    private final PaymentIntentRepository paymentIntentRepository;
    private final int windowDays;

    public SettlementCertaintyService(SettlementBatchRepository batchRepository, SettlementBatchItemRepository itemRepository,
                                      ProviderPayoutReportRepository reportRepository, BankStatementLineRepository lineRepository,
                                      LedgerJournalRepository journalRepository, ReconciliationBreakRepository breakRepository,
                                      ProviderEventRepository providerEventRepository,
                                      PaymentIntentRepository paymentIntentRepository,
                                      @Value("${payflow.reconciliation.value-date-window-days:2}") int windowDays) {
        this.batchRepository = batchRepository;
        this.itemRepository = itemRepository;
        this.reportRepository = reportRepository;
        this.lineRepository = lineRepository;
        this.journalRepository = journalRepository;
        this.breakRepository = breakRepository;
        this.providerEventRepository = providerEventRepository;
        this.paymentIntentRepository = paymentIntentRepository;
        this.windowDays = windowDays;
    }

    /** One check of the three-way match and whether it passed. Null passed means "not known yet". */
    public record Check(String name, Boolean passed, String detail) {
    }

    public record LedgerLeg(BigDecimal batchTotal, BigDecimal captures, BigDecimal deductions, List<String> journals) {
    }

    public record ProviderLeg(String providerPayoutId, BigDecimal fee, BigDecimal net, LocalDate expectedValueDate,
                              ProviderPayoutReport reported) {
    }

    public record BankLeg(String statementId, String accountServicerRef, String endToEndId, BigDecimal received,
                          BigDecimal charges, LocalDate valueDate, String status) {
    }

    public record BatchCertainty(UUID batchId, String merchantId, String currency, String payoutReference,
                                 PayoutStatus payoutStatus, BrokenReason brokenReason, LedgerLeg ledger, ProviderLeg provider,
                                 List<BankLeg> bank, List<Check> checks, List<ReconciliationBreak> breaks) {
    }

    /** A stage of a payment's money, what is known about it, and the evidence. */
    public record Stage(String stage, boolean known, Instant at, String evidence) {
    }

    @Transactional(readOnly = true)
    public BatchCertainty batch(UUID batchId) {
        SettlementBatch batch = batchRepository.findById(batchId).orElseThrow(() -> new BusinessException(
                ErrorCode.SETTLEMENT_BATCH_NOT_FOUND, "Settlement batch not found: " + batchId));
        String currency = batch.getCurrency();
        List<String> journals = new ArrayList<>();
        for (JournalType type : EnumSet.of(JournalType.PAYOUT, JournalType.PAYOUT_IN_TRANSIT, JournalType.PAYOUT_LANDED)) {
            if (journalRepository.existsByJournalTypeAndReference(type, batchId.toString())) {
                journals.add(type.name());
            }
        }
        Optional<ProviderPayoutReport> report = reportRepository.findBySettlementBatchId(batchId);
        List<BankStatementLine> lines = lineRepository.findBySettlementBatchIdOrderByImportedAtAsc(batchId);
        List<BankLeg> bank = lines.stream().map(line -> new BankLeg(line.getStatementId(), line.getAccountServicerRef(),
                line.getEndToEndId(), line.signedAmount(), line.chargesOrZero(), line.getValueDate(), line.getMatchStatus().name())).toList();

        List<Check> checks = new ArrayList<>();
        boolean accepted = batch.getProviderPayoutId() != null;
        checks.add(new Check("provider accepted the payout", accepted || !batch.isBankTracked() ? accepted : null,
                accepted ? "payout " + batch.getProviderPayoutId() + ": fee " + plain(batch.getProviderFeeAmount()) + ", net "
                        + plain(batch.getExpectedNetAmount()) + ", expected value date " + batch.getExpectedValueDate()
                        : "waiting for the provider's answer"));
        if (accepted) {
            checks.add(new Check("provider: gross - fee = net",
                    batch.getTotalAmount().subtract(batch.getProviderFeeAmount()).compareTo(batch.getExpectedNetAmount()) == 0,
                    plain(batch.getTotalAmount()) + " - " + plain(batch.getProviderFeeAmount()) + " = " + plain(batch.getExpectedNetAmount())));
        }
        checks.add(new Check("provider report agrees with the ledger", report.map(ProviderPayoutReport::isConsistent).orElse(null),
                report.map(row -> "reported gross " + plain(row.getGrossAmount()) + ", fee " + plain(row.getFeeAmount()) + ", net "
                        + plain(row.getNetAmount()) + " on " + row.getReportDate()).orElse("not reported yet")));
        Optional<BankStatementLine> landing = lines.stream().filter(line -> line.getId().equals(batch.getBankLineId())).findFirst();
        if (landing.isPresent()) {
            BankStatementLine line = landing.get();
            checks.add(new Check("bank credit carries the payout reference",
                    batch.getPayoutReference().equals(line.getEndToEndId()) ? Boolean.TRUE : null,
                    batch.getPayoutReference().equals(line.getEndToEndId()) ? line.getEndToEndId()
                            : "assigned by an operator (reference " + line.getEndToEndId() + ")"));
            checks.add(new Check("received + bank charges = provider net",
                    line.getAmount().add(line.chargesOrZero()).compareTo(batch.getExpectedNetAmount()) == 0,
                    plain(line.getAmount()) + " + " + plain(line.chargesOrZero()) + " = " + plain(batch.getExpectedNetAmount())));
            LocalDate latest = batch.getExpectedValueDate().plusDays(windowDays);
            checks.add(new Check("value date within the window", !line.getValueDate().isAfter(latest),
                    line.getValueDate() + " (expected " + batch.getExpectedValueDate() + ", latest " + latest + ")"));
            checks.add(new Check("ledger: gross = received + provider fee + bank charges",
                    line.getAmount().add(batch.getProviderFeeAmount()).add(line.chargesOrZero()).compareTo(batch.getTotalAmount()) == 0,
                    plain(batch.getTotalAmount()) + " = " + plain(line.getAmount()) + " + " + plain(batch.getProviderFeeAmount())
                            + " + " + plain(line.chargesOrZero())));
        } else if (batch.isBankTracked()) {
            checks.add(new Check("bank credit matched", null, batch.getPayoutStatus() == PayoutStatus.BROKEN
                    ? "broken: " + batch.getBrokenReason() : "no matching credit yet"));
        }
        List<SettlementBatchItem> items = itemRepository.findByBatchId(batchId);
        BigDecimal captures = items.stream().map(SettlementBatchItem::getAmount).filter(amount -> amount.signum() > 0)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal deductions = items.stream().map(SettlementBatchItem::getAmount).filter(amount -> amount.signum() < 0)
                .reduce(BigDecimal.ZERO, BigDecimal::add).negate();
        return new BatchCertainty(batch.getId(), batch.getMerchantId(), currency, batch.getPayoutReference(),
                batch.getPayoutStatus(), batch.getBrokenReason(),
                new LedgerLeg(batch.getTotalAmount(), captures, deductions, journals),
                new ProviderLeg(batch.getProviderPayoutId(), batch.getProviderFeeAmount(), batch.getExpectedNetAmount(),
                        batch.getExpectedValueDate(), report.orElse(null)),
                bank, checks, breakRepository.findBySettlementBatchIdOrderByOpenedAtAsc(batchId));
    }

    /**
     * What is known about a payment's money, stage by stage: authorized, captured, paid out by the provider,
     * landed in the bank, reconciled. Each known stage names the evidence that proves it.
     */
    @Transactional(readOnly = true)
    public List<Stage> payment(UUID paymentIntentId) {
        PaymentIntent intent = paymentIntentRepository.findById(paymentIntentId).orElseThrow();
        List<ProviderEvent> events = providerEventRepository.findByPaymentIntentIdOrderByReceivedAtAsc(paymentIntentId);
        Optional<ProviderEvent> authorized = events.stream()
                .filter(event -> event.getOutcome() == WebhookOutcome.APPLIED
                        && (event.getStatus() == ProviderStatus.AUTHORIZED || event.getStatus() == ProviderStatus.SUCCESS))
                .findFirst();
        Optional<LedgerJournal> capture = journalRepository.findByPaymentIntentIdOrderByCreatedAtAsc(paymentIntentId).stream()
                .filter(journal -> journal.getJournalType() == JournalType.CAPTURE).findFirst();
        Optional<SettlementBatch> batch = itemRepository.findByCapturePaymentIntentId(paymentIntentId)
                .flatMap(item -> batchRepository.findById(item.getBatchId()));

        List<Stage> stages = new ArrayList<>();
        stages.add(new Stage("AUTHORIZED", authorized.isPresent(), authorized.map(ProviderEvent::getReceivedAt).orElse(null),
                authorized.map(event -> event.getSource() + " event " + event.getProviderEventId() + " (" + event.getStatus() + ")")
                        .orElse("no authorization or capture event from the provider")));
        stages.add(new Stage("CAPTURED", capture.isPresent(), intent.getCapturedAt(),
                capture.map(journal -> "CAPTURE journal " + journal.getId()).orElse("no capture journal")));
        boolean paidOut = batch.isPresent() && (batch.get().getProviderPayoutId() != null || !batch.get().isBankTracked());
        stages.add(new Stage("PAID_OUT_BY_PROVIDER", paidOut, batch.map(SettlementBatch::getInTransitAt).orElse(null),
                batch.map(b -> b.isBankTracked()
                        ? (b.getProviderPayoutId() == null ? "batch " + b.getPayoutReference() + " sent, provider has not accepted it yet"
                        : "provider payout " + b.getProviderPayoutId() + " for batch " + b.getPayoutReference())
                        : "batch " + b.getId() + " paid out before bank tracking").orElse("not in a settlement batch yet")));
        boolean landed = batch.isPresent() && batch.get().getPayoutStatus() == PayoutStatus.LANDED;
        stages.add(new Stage("LANDED_IN_BANK", landed, batch.map(SettlementBatch::getLandedAt).orElse(null),
                landed ? lineRepository.findById(batch.get().getBankLineId())
                        .map(line -> "bank line " + line.getAccountServicerRef() + " in statement " + line.getStatementId()
                                + ", value date " + line.getValueDate())
                        .orElse("bank line " + batch.get().getBankLineId())
                        : batch.map(b -> "payout " + b.getPayoutStatus() + (b.getBrokenReason() == null ? "" : " (" + b.getBrokenReason() + ")"))
                        .orElse("no payout yet")));
        boolean reconciled = intent.getState() == PaymentState.RECONCILED;
        stages.add(new Stage("RECONCILED", reconciled, null, reconciled
                ? "provider report matched on " + intent.getProviderReportMatchedAt() + " and the payout landed"
                : intent.getProviderReportMatchedAt() == null ? "provider report not matched yet"
                : "provider report matched; waiting for the bank"));
        return stages;
    }

    private static String plain(BigDecimal amount) {
        return amount == null ? "?" : amount.toPlainString();
    }
}
