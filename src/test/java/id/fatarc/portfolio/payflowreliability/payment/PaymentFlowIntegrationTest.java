package id.fatarc.portfolio.payflowreliability.payment;

import id.fatarc.portfolio.payflowreliability.audit.AuditEventRepository;
import id.fatarc.portfolio.payflowreliability.common.BusinessException;
import id.fatarc.portfolio.payflowreliability.payment.dto.CreatePaymentIntentRequest;
import id.fatarc.portfolio.payflowreliability.payment.dto.ProviderWebhookRequest;
import id.fatarc.portfolio.payflowreliability.reconciliation.ReconciliationService;
import id.fatarc.portfolio.payflowreliability.reconciliation.dto.ReconciliationReportRequest;
import id.fatarc.portfolio.payflowreliability.reconciliation.dto.ReconciliationReportResponse;
import id.fatarc.portfolio.payflowreliability.refund.RefundService;
import id.fatarc.portfolio.payflowreliability.refund.dto.CreateRefundRequest;
import id.fatarc.portfolio.payflowreliability.review.ReviewCaseRepository;
import id.fatarc.portfolio.payflowreliability.review.ReviewReason;
import id.fatarc.portfolio.payflowreliability.review.ReviewStatus;
import id.fatarc.portfolio.payflowreliability.settlement.SettlementBatch;
import id.fatarc.portfolio.payflowreliability.settlement.SettlementBatchItemRepository;
import id.fatarc.portfolio.payflowreliability.settlement.SettlementService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@Transactional
class PaymentFlowIntegrationTest {
    private static final String SIGNATURE = "local-demo-signature";

    @Autowired
    private PaymentIntentService paymentIntentService;

    @Autowired
    private ProviderWebhookService providerWebhookService;

    @Autowired
    private PaymentIntentRepository paymentIntentRepository;

    @Autowired
    private PaymentTransactionRepository paymentTransactionRepository;

    @Autowired
    private ReviewCaseRepository reviewCaseRepository;

    @Autowired
    private AuditEventRepository auditEventRepository;

    @Autowired
    private SettlementService settlementService;

    @Autowired
    private SettlementBatchItemRepository settlementBatchItemRepository;

    @Autowired
    private ReconciliationService reconciliationService;

    @Autowired
    private RefundService refundService;

    @Test
    void shouldIgnoreDuplicateSuccessWebhook() {
        PaymentIntent intent = createStartedIntent("flow-key-001", "ORDER-1001", new BigDecimal("125000"));

        ProviderWebhookRequest webhook = new ProviderWebhookRequest(
                "evt-001",
                "provider-tx-001",
                intent.getId(),
                ProviderStatus.SUCCESS,
                new BigDecimal("125000"),
                "IDR"
        );

        PaymentIntent firstResult = providerWebhookService.process(webhook, SIGNATURE);
        PaymentIntent secondResult = providerWebhookService.process(webhook, SIGNATURE);

        assertThat(firstResult.getState()).isEqualTo(PaymentState.SUCCESS);
        assertThat(secondResult.getState()).isEqualTo(PaymentState.SUCCESS);
        assertThat(paymentTransactionRepository.findAll()).hasSize(1);
        assertThat(openReviewReasons()).contains(ReviewReason.DUPLICATE_PROVIDER_EVENT);
    }

    @Test
    void shouldRejectInvalidTransactionStateTransition() {
        PaymentIntent intent = createStartedIntent("flow-key-002", "ORDER-1002", new BigDecimal("225000"));

        providerWebhookService.process(new ProviderWebhookRequest(
                "evt-002-success",
                "provider-tx-002",
                intent.getId(),
                ProviderStatus.SUCCESS,
                new BigDecimal("225000"),
                "IDR"
        ), SIGNATURE);

        PaymentIntent result = providerWebhookService.process(new ProviderWebhookRequest(
                "evt-002-failed",
                "provider-tx-002-late",
                intent.getId(),
                ProviderStatus.FAILED,
                new BigDecimal("225000"),
                "IDR"
        ), SIGNATURE);

        assertThat(result.getState()).isEqualTo(PaymentState.SUCCESS);
        assertThat(openReviewReasons()).contains(ReviewReason.INVALID_STATE_TRANSITION);
    }

    @Test
    void shouldCreateManualReviewForAmountMismatch() {
        PaymentIntent intent = createStartedIntent("flow-key-003", "ORDER-1003", new BigDecimal("99000"));

        PaymentIntent result = providerWebhookService.process(new ProviderWebhookRequest(
                "evt-003",
                "provider-tx-003",
                intent.getId(),
                ProviderStatus.SUCCESS,
                new BigDecimal("98000"),
                "IDR"
        ), SIGNATURE);

        assertThat(result.getState()).isEqualTo(PaymentState.MANUAL_REVIEW);
        assertThat(openReviewReasons()).contains(ReviewReason.AMOUNT_MISMATCH);
    }

    @Test
    void shouldCreateAuditEventForEveryStateChange() {
        PaymentIntent intent = createStartedIntent("flow-key-004", "ORDER-1004", new BigDecimal("150000"));

        providerWebhookService.process(new ProviderWebhookRequest(
                "evt-004-auth",
                "provider-tx-004-auth",
                intent.getId(),
                ProviderStatus.AUTHORIZED,
                new BigDecimal("150000"),
                "IDR"
        ), SIGNATURE);

        providerWebhookService.process(new ProviderWebhookRequest(
                "evt-004-success",
                "provider-tx-004-success",
                intent.getId(),
                ProviderStatus.SUCCESS,
                new BigDecimal("150000"),
                "IDR"
        ), SIGNATURE);

        assertThat(auditEventRepository.findAll())
                .filteredOn(event -> "TRANSACTION_STATE_CHANGED".equals(event.getEventType()))
                .extracting(event -> event.getPreviousState() + "->" + event.getNewState())
                .contains("CREATED->PENDING", "PENDING->AUTHORIZED", "AUTHORIZED->SUCCESS");
    }

    @Test
    void shouldCreateSettlementBatchAndMarkPaymentAsSettled() {
        PaymentIntent intent = createStartedIntent("flow-key-005", "ORDER-1005", new BigDecimal("77000"));

        providerWebhookService.process(new ProviderWebhookRequest(
                "evt-005",
                "provider-tx-005",
                intent.getId(),
                ProviderStatus.SUCCESS,
                new BigDecimal("77000"),
                "IDR"
        ), SIGNATURE);

        SettlementBatch batch = settlementService.createBatch(intent.getMerchantId());
        PaymentIntent settledIntent = paymentIntentRepository.findById(intent.getId()).orElseThrow();

        assertThat(batch.getTransactionCount()).isEqualTo(1);
        assertThat(settlementBatchItemRepository.findByBatchId(batch.getId())).hasSize(1);
        assertThat(settledIntent.getState()).isEqualTo(PaymentState.SETTLED);
    }

    @Test
    void shouldReconcileMatchingSettledTransaction() {
        PaymentIntent intent = createSettledIntent("flow-key-006", "ORDER-1006", new BigDecimal("64000"));
        PaymentTransaction transaction = paymentTransactionRepository.findAll().getFirst();

        ReconciliationReportResponse result = reconciliationService.importReport(new ReconciliationReportRequest(List.of(
                new ReconciliationReportRequest.Row(
                        transaction.getProviderTransactionId(),
                        new BigDecimal("64000"),
                        "IDR",
                        ProviderStatus.SUCCESS,
                        transaction.getSettledOn()
                )
        )));

        PaymentIntent reconciledIntent = paymentIntentRepository.findById(intent.getId()).orElseThrow();

        assertThat(result.mismatchCount()).isEqualTo(0);
        assertThat(reconciledIntent.getState()).isEqualTo(PaymentState.RECONCILED);
    }

    @Test
    void shouldCreateManualReviewForReconciliationMismatch() {
        createSettledIntent("flow-key-007", "ORDER-1007", new BigDecimal("88000"));
        PaymentTransaction transaction = paymentTransactionRepository.findAll().getFirst();

        ReconciliationReportResponse result = reconciliationService.importReport(new ReconciliationReportRequest(List.of(
                new ReconciliationReportRequest.Row(
                        transaction.getProviderTransactionId(),
                        new BigDecimal("87000"),
                        "IDR",
                        ProviderStatus.SUCCESS,
                        LocalDate.now().minusDays(1)
                )
        )));

        assertThat(result.mismatchCount()).isEqualTo(2);
        assertThat(openReviewReasons()).contains(ReviewReason.AMOUNT_MISMATCH, ReviewReason.SETTLEMENT_DATE_MISMATCH);
    }

    @Test
    void shouldPreventRefundAfterReconciliationWithoutReview() {
        PaymentIntent intent = createSettledIntent("flow-key-008", "ORDER-1008", new BigDecimal("91000"));
        PaymentTransaction transaction = paymentTransactionRepository.findAll().getFirst();

        reconciliationService.importReport(new ReconciliationReportRequest(List.of(
                new ReconciliationReportRequest.Row(
                        transaction.getProviderTransactionId(),
                        new BigDecimal("91000"),
                        "IDR",
                        ProviderStatus.SUCCESS,
                        transaction.getSettledOn()
                )
        )));

        assertThatThrownBy(() -> refundService.requestRefund(
                intent.getId(),
                new CreateRefundRequest(new BigDecimal("91000"), "customer refund")
        ))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("Refund requires manual review after reconciliation");
        assertThat(openReviewReasons()).contains(ReviewReason.REFUND_BLOCKED_AFTER_RECONCILIATION);
    }

    @Test
    void shouldReuseOpenReviewCaseForRepeatedOperationalMismatch() {
        PaymentIntent intent = createStartedIntent("flow-key-009", "ORDER-1009", new BigDecimal("48000"));

        ProviderWebhookRequest webhook = new ProviderWebhookRequest(
                "evt-009",
                "provider-tx-009",
                intent.getId(),
                ProviderStatus.SUCCESS,
                new BigDecimal("47000"),
                "IDR"
        );

        providerWebhookService.process(webhook, SIGNATURE);
        providerWebhookService.process(webhook, SIGNATURE);

        assertThat(reviewCaseRepository.findByStatusOrderByCreatedAtAsc(ReviewStatus.OPEN))
                .filteredOn(review -> review.getReason() == ReviewReason.AMOUNT_MISMATCH)
                .hasSize(1);
    }

    private PaymentIntent createStartedIntent(String idempotencyKey, String merchantReference, BigDecimal amount) {
        PaymentIntent createdIntent = paymentIntentService.create(new CreatePaymentIntentRequest(
                "merchant-demo-01",
                merchantReference,
                amount,
                "IDR"
        ), idempotencyKey);

        return paymentIntentService.start(createdIntent.getId());
    }

    private PaymentIntent createSettledIntent(String idempotencyKey, String merchantReference, BigDecimal amount) {
        PaymentIntent intent = createStartedIntent(idempotencyKey, merchantReference, amount);

        providerWebhookService.process(new ProviderWebhookRequest(
                "evt-" + merchantReference + "-success",
                "provider-tx-" + merchantReference,
                intent.getId(),
                ProviderStatus.SUCCESS,
                amount,
                "IDR"
        ), SIGNATURE);

        settlementService.createBatch(intent.getMerchantId());

        return paymentIntentRepository.findById(intent.getId()).orElseThrow();
    }

    private List<ReviewReason> openReviewReasons() {
        return reviewCaseRepository.findByStatusOrderByCreatedAtAsc(ReviewStatus.OPEN).stream()
                .map(review -> review.getReason())
                .toList();
    }
}
