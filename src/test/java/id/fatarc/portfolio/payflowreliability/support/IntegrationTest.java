package id.fatarc.portfolio.payflowreliability.support;

import id.fatarc.portfolio.payflowreliability.audit.AuditEvent;
import id.fatarc.portfolio.payflowreliability.audit.AuditEventRepository;
import id.fatarc.portfolio.payflowreliability.payment.*;
import id.fatarc.portfolio.payflowreliability.payment.dto.CreatePaymentIntentRequest;
import id.fatarc.portfolio.payflowreliability.payment.dto.ProviderWebhookRequest;
import id.fatarc.portfolio.payflowreliability.reconciliation.ReconciliationService;
import id.fatarc.portfolio.payflowreliability.reconciliation.dto.ReconciliationReportRequest;
import id.fatarc.portfolio.payflowreliability.review.ReviewCase;
import id.fatarc.portfolio.payflowreliability.review.ReviewCaseRepository;
import id.fatarc.portfolio.payflowreliability.review.ReviewReason;
import id.fatarc.portfolio.payflowreliability.review.ReviewStatus;
import id.fatarc.portfolio.payflowreliability.review.ReviewWorkflowService;
import id.fatarc.portfolio.payflowreliability.settlement.SettlementService;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.*;

/**
 * Base for tests that exercise real transaction boundaries. Tests are deliberately NOT wrapped in a
 * test-managed transaction: each service call commits or rolls back exactly as it would in production.
 * (A class-level @Transactional previously hid review cases being rolled back on rejection paths.)
 */
@SpringBootTest
public abstract class IntegrationTest {
    protected static final String SIGNATURE = "local-demo-signature";
    protected static final String MERCHANT = "merchant-demo-01";

    @Autowired protected JdbcTemplate jdbcTemplate;
    @Autowired protected PaymentIntentService paymentIntentService;
    @Autowired protected ProviderWebhookService providerWebhookService;
    @Autowired protected PaymentIntentRepository paymentIntentRepository;
    @Autowired protected PaymentTransactionRepository paymentTransactionRepository;
    @Autowired protected ProviderEventRepository providerEventRepository;
    @Autowired protected ReviewCaseRepository reviewCaseRepository;
    @Autowired protected AuditEventRepository auditEventRepository;
    @Autowired protected SettlementService settlementService;
    @Autowired protected ReviewWorkflowService reviewWorkflowService;
    @Autowired protected ReconciliationService reconciliationService;

    @BeforeEach
    void cleanDatabase() {
        for (String table : List.of("settlement_batch_items", "settlement_batches", "provider_events",
                "refund_requests", "review_cases", "payment_transactions", "audit_events", "payment_intents")) {
            jdbcTemplate.update("delete from " + table);
        }
    }

    protected PaymentIntent createIntent(String reference, String amount) {
        return createIntent(MERCHANT, reference, amount, "IDR");
    }

    protected PaymentIntent createIntent(String merchantId, String reference, String amount, String currency) {
        return paymentIntentService.create(
                new CreatePaymentIntentRequest(merchantId, reference, new BigDecimal(amount), currency),
                "key-" + reference
        ).intent();
    }

    protected PaymentIntent startedIntent(String reference, String amount) {
        return paymentIntentService.start(createIntent(reference, amount).getId());
    }

    protected PaymentIntent startedIntent(String merchantId, String reference, String amount, String currency) {
        return paymentIntentService.start(createIntent(merchantId, reference, amount, currency).getId());
    }

    protected WebhookResult webhook(UUID paymentIntentId, String eventId, String providerTransactionId,
                                    ProviderStatus status, String amount) {
        return webhook(paymentIntentId, eventId, providerTransactionId, status, amount, "IDR");
    }

    protected WebhookResult webhook(UUID paymentIntentId, String eventId, String providerTransactionId,
                                    ProviderStatus status, String amount, String currency) {
        return providerWebhookService.process(new ProviderWebhookRequest(
                eventId, providerTransactionId, paymentIntentId, status, new BigDecimal(amount), currency), SIGNATURE);
    }

    /** A payment captured by the provider (SUCCESS) with provider transaction id {@code tx-<reference>}. */
    protected PaymentIntent capturedIntent(String reference, String amount) {
        PaymentIntent intent = startedIntent(reference, amount);
        webhook(intent.getId(), "evt-" + reference + "-success", "tx-" + reference, ProviderStatus.SUCCESS, amount);
        return reload(intent);
    }

    protected PaymentIntent settledIntent(String reference, String amount) {
        PaymentIntent intent = capturedIntent(reference, amount);
        settlementService.createBatch(intent.getMerchantId(), null);
        return reload(intent);
    }

    /** A settled payment matched by a clean provider report row. */
    protected PaymentIntent reconciledIntent(String reference, String amount) {
        PaymentIntent intent = settledIntent(reference, amount);
        PaymentTransaction transaction = paymentTransactionRepository.findByPaymentIntentIdOrderByCreatedAtAsc(intent.getId()).getFirst();
        reconciliationService.importReport(new ReconciliationReportRequest(List.of(new ReconciliationReportRequest.Row(
                transaction.getProviderTransactionId(), new BigDecimal(amount), transaction.getCurrency(),
                ProviderStatus.SUCCESS, transaction.getSettledOn()))));
        return reload(intent);
    }

    protected PaymentIntent reload(PaymentIntent intent) {
        return paymentIntentRepository.findById(intent.getId()).orElseThrow();
    }

    protected PaymentState stateOf(PaymentIntent intent) {
        return reload(intent).getState();
    }

    protected List<ReviewCase> openCases() {
        return reviewCaseRepository.findByStatusOrderByCreatedAtAsc(ReviewStatus.OPEN);
    }

    protected List<ReviewReason> openReviewReasons() {
        return openCases().stream().map(ReviewCase::getReason).toList();
    }

    protected List<AuditEvent> auditFor(PaymentIntent intent) {
        return auditEventRepository.findByPaymentIntentIdOrderByCreatedAtAsc(intent.getId());
    }

    /** Runs the task on {@code threads} threads released at the same instant; returns each result or the thrown exception. */
    protected static <T> List<Object> race(int threads, Callable<T> task) throws InterruptedException {
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Object>> futures = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            futures.add(pool.submit(() -> {
                start.await();
                try {
                    return task.call();
                } catch (Exception ex) {
                    return ex;
                }
            }));
        }
        start.countDown();
        List<Object> results = new ArrayList<>();
        for (Future<Object> future : futures) {
            try {
                results.add(future.get(30, TimeUnit.SECONDS));
            } catch (ExecutionException | TimeoutException ex) {
                results.add(ex);
            }
        }
        pool.shutdownNow();
        return results;
    }
}
