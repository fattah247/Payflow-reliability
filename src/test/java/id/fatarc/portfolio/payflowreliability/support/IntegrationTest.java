package id.fatarc.portfolio.payflowreliability.support;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import id.fatarc.portfolio.payflowreliability.audit.AuditChainVerifier;
import id.fatarc.portfolio.payflowreliability.audit.AuditEvent;
import id.fatarc.portfolio.payflowreliability.audit.AuditEventRepository;
import id.fatarc.portfolio.payflowreliability.bank.BankStatementImportService;
import id.fatarc.portfolio.payflowreliability.bank.StatementImportResult;
import id.fatarc.portfolio.payflowreliability.banksim.BankFaults;
import id.fatarc.portfolio.payflowreliability.banksim.BankSimulator;
import id.fatarc.portfolio.payflowreliability.common.WebhookSignatures;
import id.fatarc.portfolio.payflowreliability.ledger.LedgerInvariantService;
import id.fatarc.portfolio.payflowreliability.ledger.LedgerJournalRepository;
import id.fatarc.portfolio.payflowreliability.ledger.LedgerQueryService;
import id.fatarc.portfolio.payflowreliability.merchant.MerchantService;
import id.fatarc.portfolio.payflowreliability.merchant.dto.RegisterMerchantRequest;
import id.fatarc.portfolio.payflowreliability.outbox.OutboxCommandRepository;
import id.fatarc.portfolio.payflowreliability.outbox.OutboxService;
import id.fatarc.portfolio.payflowreliability.outbox.OutboxWorker;
import id.fatarc.portfolio.payflowreliability.payment.*;
import id.fatarc.portfolio.payflowreliability.payment.dto.CreatePaymentIntentRequest;
import id.fatarc.portfolio.payflowreliability.payment.dto.ProviderWebhookRequest;
import id.fatarc.portfolio.payflowreliability.providersim.ProviderSimulator;
import id.fatarc.portfolio.payflowreliability.providersim.SimApiRequestRepository;
import id.fatarc.portfolio.payflowreliability.providersim.SimFaults;
import id.fatarc.portfolio.payflowreliability.providersim.SimOperationRepository;
import id.fatarc.portfolio.payflowreliability.providersim.SimWebhookDispatcher;
import id.fatarc.portfolio.payflowreliability.reconciliation.ReconciliationService;
import id.fatarc.portfolio.payflowreliability.reconciliation.ThreeWayReconciliationService;
import id.fatarc.portfolio.payflowreliability.reconciliation.breaks.BreakStatus;
import id.fatarc.portfolio.payflowreliability.reconciliation.breaks.ReconciliationBreak;
import id.fatarc.portfolio.payflowreliability.reconciliation.breaks.ReconciliationBreakRepository;
import id.fatarc.portfolio.payflowreliability.reconciliation.dto.ReconciliationReportRequest;
import id.fatarc.portfolio.payflowreliability.refund.RefundRequest;
import id.fatarc.portfolio.payflowreliability.refund.RefundRequestRepository;
import id.fatarc.portfolio.payflowreliability.refund.RefundService;
import id.fatarc.portfolio.payflowreliability.refund.dto.CreateRefundRequest;
import id.fatarc.portfolio.payflowreliability.review.ReviewCase;
import id.fatarc.portfolio.payflowreliability.review.ReviewCaseRepository;
import id.fatarc.portfolio.payflowreliability.review.ReviewReason;
import id.fatarc.portfolio.payflowreliability.review.ReviewStatus;
import id.fatarc.portfolio.payflowreliability.review.ReviewWorkflowService;
import id.fatarc.portfolio.payflowreliability.security.Caller;
import id.fatarc.portfolio.payflowreliability.settlement.PayoutStatus;
import id.fatarc.portfolio.payflowreliability.settlement.SettlementBatch;
import id.fatarc.portfolio.payflowreliability.settlement.SettlementBatchRepository;
import id.fatarc.portfolio.payflowreliability.settlement.SettlementService;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.*;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * Base for tests that exercise real transaction boundaries, real HTTP (the provider simulator and its
 * webhooks run over this server's port) and real security. Tests are deliberately NOT wrapped in a
 * test-managed transaction: each service call commits or rolls back exactly as it would in production.
 * Background workers are off (see application-test.yml); tests run each job explicitly.
 */
@ActiveProfiles("test")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@Import({TestClockConfig.class, TestProviderClientConfig.class})
public abstract class IntegrationTest {
    protected static final String MERCHANT_A = "merchant-a";
    protected static final String MERCHANT_B = "merchant-b";
    protected static final Caller AS_MERCHANT_A = Caller.merchant(MERCHANT_A);
    protected static final Caller AS_MERCHANT_B = Caller.merchant(MERCHANT_B);
    protected static final Caller OPS_ANA = Caller.operator("ops-ana");
    protected static final Caller OPS_BUDI = Caller.operator("ops-budi");
    protected static final String OPS_ANA_KEY = "pf_o_test_ops-ana";
    protected static final String OPS_BUDI_KEY = "pf_o_test_ops-budi";
    protected static final String WEBHOOK_SECRET = "whsec_test_current";
    protected static final String PREVIOUS_WEBHOOK_SECRET = "whsec_test_previous";

    private static final List<String> TABLES = List.of("reconciliation_breaks", "provider_payout_reports",
            "bank_statement_lines", "bank_statements", "bank_accounts", "sim_payout_items", "sim_payouts", "bank_sim_entries",
            "bank_sim_statements", "bank_sim_accounts", "settlement_batch_items", "settlement_batches", "ledger_postings",
            "ledger_journals", "outbox_commands", "provider_events", "refund_requests", "review_cases",
            "payment_transactions", "audit_events", "idempotency_records", "payment_intents", "merchant_api_keys",
            "merchants", "sim_webhook_deliveries", "sim_operations", "sim_payments", "sim_api_requests");

    @Autowired protected MockMvc mockMvc;
    @Autowired protected ObjectMapper objectMapper;
    @Autowired protected JdbcTemplate jdbcTemplate;
    @Autowired protected PlatformTransactionManager transactionManager;
    @Autowired protected MutableClock clock;
    @Autowired protected MerchantService merchantService;
    @Autowired protected PaymentIntentService paymentIntentService;
    @Autowired protected ProviderWebhookService providerWebhookService;
    @Autowired protected PaymentIntentRepository paymentIntentRepository;
    @Autowired protected PaymentTransactionRepository paymentTransactionRepository;
    @Autowired protected ProviderEventRepository providerEventRepository;
    @Autowired protected ReviewCaseRepository reviewCaseRepository;
    @Autowired protected AuditEventRepository auditEventRepository;
    @Autowired protected AuditChainVerifier auditChainVerifier;
    @Autowired protected SettlementService settlementService;
    @Autowired protected ReviewWorkflowService reviewWorkflowService;
    @Autowired protected ReconciliationService reconciliationService;
    @Autowired protected RefundService refundService;
    @Autowired protected RefundRequestRepository refundRequestRepository;
    @Autowired protected OutboxService outboxService;
    @Autowired protected OutboxWorker outboxWorker;
    @Autowired protected OutboxCommandRepository outboxCommandRepository;
    @Autowired protected LedgerJournalRepository ledgerJournalRepository;
    @Autowired protected LedgerQueryService ledgerQueryService;
    @Autowired protected LedgerInvariantService ledgerInvariantService;
    @Autowired protected PaymentStatusSweeper sweeper;
    @Autowired protected ProviderSimulator providerSimulator;
    @Autowired protected SimWebhookDispatcher webhookDispatcher;
    @Autowired protected SimOperationRepository simOperationRepository;
    @Autowired protected SimFaults simFaults;
    @Autowired protected SimApiRequestRepository simApiRequestRepository;
    @Autowired protected CrashableProviderClient providerClient;
    @Autowired protected BankSimulator bankSimulator;
    @Autowired protected BankFaults bankFaults;
    @Autowired protected BankStatementImportService bankStatementImportService;
    @Autowired protected ThreeWayReconciliationService threeWayReconciliationService;
    @Autowired protected ReconciliationBreakRepository breakRepository;
    @Autowired protected SettlementBatchRepository settlementBatchRepository;

    protected String merchantAKey;
    protected String merchantBKey;

    @BeforeEach
    protected void resetWorld() {
        clock.reset();
        simFaults.clear();
        bankFaults.clear();
        providerClient.reset();
        truncateAll();
        merchantAKey = merchantService.register(new RegisterMerchantRequest(MERCHANT_A, "Merchant A"), OPS_ANA).apiKey().apiKey();
        merchantBKey = merchantService.register(new RegisterMerchantRequest(MERCHANT_B, "Merchant B"), OPS_ANA).apiKey().apiKey();
    }

    private void truncateAll() {
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            if (isPostgres()) {
                // Evidence tables are append-only in PostgreSQL; a test reset is the documented maintenance bypass.
                jdbcTemplate.execute("set local payflow.evidence_maintenance = 'on'");
                jdbcTemplate.execute("truncate table " + String.join(", ", TABLES) + " cascade");
            } else {
                jdbcTemplate.execute("set referential_integrity false");
                TABLES.forEach(table -> jdbcTemplate.execute("truncate table " + table));
                jdbcTemplate.execute("set referential_integrity true");
            }
        });
    }

    protected boolean isPostgres() {
        return Boolean.TRUE.equals(jdbcTemplate.execute((Connection connection) ->
                connection.getMetaData().getDatabaseProductName().toLowerCase().contains("postgres")));
    }

    // ---- payments -------------------------------------------------------------------------------

    protected PaymentIntent createIntent(String reference, String amount) {
        return createIntent(MERCHANT_A, reference, amount, "IDR");
    }

    protected PaymentIntent createIntent(String merchantId, String reference, String amount, String currency) {
        return paymentIntentService.create(
                new CreatePaymentIntentRequest(null, reference, new BigDecimal(amount), currency),
                "key-" + reference, Caller.merchant(merchantId)).intent();
    }

    protected PaymentIntent startedIntent(String reference, String amount) {
        return startedIntent(MERCHANT_A, reference, amount, "IDR");
    }

    protected PaymentIntent startedIntent(String merchantId, String reference, String amount, String currency) {
        PaymentIntent intent = createIntent(merchantId, reference, amount, currency);
        return paymentIntentService.start(intent.getId(), Caller.merchant(merchantId));
    }

    protected WebhookResult webhook(UUID paymentIntentId, String eventId, String providerTransactionId,
                                    ProviderStatus status, String amount) {
        return webhook(paymentIntentId, eventId, providerTransactionId, status, amount, "IDR");
    }

    /** Applies a provider event directly (signature verification is covered by the HTTP tests). */
    protected WebhookResult webhook(UUID paymentIntentId, String eventId, String providerTransactionId,
                                    ProviderStatus status, String amount, String currency) {
        return providerWebhookService.process(new ProviderWebhookRequest(
                eventId, providerTransactionId, paymentIntentId, status, new BigDecimal(amount), currency));
    }

    /** A payment captured by a provider event applied directly (the simulator does not know about it). */
    protected PaymentIntent capturedIntent(String reference, String amount) {
        PaymentIntent intent = startedIntent(reference, amount);
        webhook(intent.getId(), "evt-" + reference + "-success", "tx-" + reference, ProviderStatus.SUCCESS, amount);
        return reload(intent);
    }

    /** A payment the customer paid at the provider simulator, applied through a real signed webhook. */
    protected PaymentIntent paidIntent(String reference, String amount) {
        return paidIntent(MERCHANT_A, reference, amount, "IDR");
    }

    protected PaymentIntent paidIntent(String merchantId, String reference, String amount, String currency) {
        PaymentIntent intent = startedIntent(merchantId, reference, amount, currency);
        providerSimulator.pay(intent.getId(), new BigDecimal(amount), currency, null);
        deliverWebhooks();
        return reload(intent);
    }

    protected SettlementBatch settle(String merchantId) {
        return settlementService.createBatch(merchantId, null, OPS_ANA);
    }

    protected PaymentIntent settledIntent(String reference, String amount) {
        PaymentIntent intent = paidIntent(reference, amount);
        settle(intent.getMerchantId());
        return reload(intent);
    }

    // ---- the bank leg ---------------------------------------------------------------------------

    /**
     * What happens after a batch is created, compressed: the provider executes the payout (outbox), the bank books
     * the credit, the bank closes business days through the value date, and the statements are imported.
     */
    protected List<StatementImportResult> landPayouts() {
        drainOutbox();
        for (SettlementBatch batch : settlementBatchRepository.findByPayoutStatusInOrderByCreatedAtAsc(
                java.util.EnumSet.of(PayoutStatus.IN_TRANSIT))) {
            bankSimulator.closeDays(batch.getCurrency(), batch.getExpectedValueDate());
        }
        return importStatements();
    }

    /** Closes the bank's business days through {@code date}, issuing one statement per day. */
    protected void closeBankDays(String currency, LocalDate date) {
        bankSimulator.closeDays(currency, date);
    }

    protected List<StatementImportResult> importStatements() {
        return bankStatementImportService.pull(OPS_ANA.name());
    }

    protected SettlementBatch reload(SettlementBatch batch) {
        return settlementBatchRepository.findById(batch.getId()).orElseThrow();
    }

    protected List<ReconciliationBreak> openBreaks() {
        return breakRepository.findByStatusOrderByOpenedAtAsc(BreakStatus.OPEN);
    }

    /** A settled payment matched by a clean provider report row, whose payout has landed in the bank. */
    protected PaymentIntent reconciledIntent(String reference, String amount) {
        PaymentIntent intent = settledIntent(reference, amount);
        landPayouts();
        PaymentTransaction transaction = transactionOf(intent);
        reconciliationService.importReport(new ReconciliationReportRequest(List.of(new ReconciliationReportRequest.Row(
                transaction.getProviderTransactionId(), new BigDecimal(amount), transaction.getCurrency(),
                ProviderStatus.SUCCESS, transaction.getSettledOn()))), OPS_ANA);
        return reload(intent);
    }

    protected PaymentTransaction transactionOf(PaymentIntent intent) {
        return paymentTransactionRepository.findByPaymentIntentIdOrderByCreatedAtAsc(intent.getId()).getFirst();
    }

    protected RefundRequest requestRefund(PaymentIntent intent, String amount, String key) {
        return refundService.requestRefund(intent.getId(), new CreateRefundRequest(new BigDecimal(amount), "Customer request"),
                key, Caller.merchant(intent.getMerchantId())).refund();
    }

    protected PaymentIntent reload(PaymentIntent intent) {
        return paymentIntentRepository.findById(intent.getId()).orElseThrow();
    }

    protected RefundRequest reload(RefundRequest refund) {
        return refundRequestRepository.findById(refund.getId()).orElseThrow();
    }

    protected PaymentState stateOf(PaymentIntent intent) {
        return reload(intent).getState();
    }

    protected List<ReviewCase> openCases() {
        return reviewCaseRepository.findByStatusInOrderByCreatedAtAsc(ReviewStatus.UNRESOLVED);
    }

    protected List<ReviewReason> openReviewReasons() {
        return openCases().stream().map(ReviewCase::getReason).toList();
    }

    protected List<AuditEvent> auditFor(PaymentIntent intent) {
        return auditEventRepository.findByPaymentIntentIdOrderBySequenceNumberAsc(intent.getId());
    }

    // ---- background jobs, run explicitly ----------------------------------------------------------

    /** Delivers every due simulated webhook, including retries, until nothing is left to send. */
    protected void deliverWebhooks() {
        for (int round = 0; round < 20 && webhookDispatcher.runOnce() > 0; round++) {
            clock.advance(Duration.ofSeconds(1));
        }
    }

    /** Runs the outbox until no command is due, moving time past each backoff. Returns commands processed. */
    protected int drainOutbox() {
        int total = 0;
        for (int round = 0; round < 20; round++) {
            int processed = outboxWorker.runOnce();
            if (processed == 0) {
                return total;
            }
            total += processed;
            clock.advance(Duration.ofSeconds(1));
        }
        return total;
    }

    // ---- HTTP -----------------------------------------------------------------------------------

    protected static MockHttpServletRequestBuilder as(String apiKey, MockHttpServletRequestBuilder request) {
        return request.header("Authorization", "Bearer " + apiKey);
    }

    protected static MockHttpServletRequestBuilder json(MockHttpServletRequestBuilder request, String body) {
        return request.contentType(MediaType.APPLICATION_JSON).content(body);
    }

    protected ResultActions postIntent(String apiKey, String idempotencyKey, String body) throws Exception {
        MockHttpServletRequestBuilder request = json(as(apiKey, post("/api/payment-intents")), body);
        if (idempotencyKey != null) {
            request.header("Idempotency-Key", idempotencyKey);
        }
        return mockMvc.perform(request);
    }

    protected ResultActions postSignedWebhook(String body) throws Exception {
        return postWebhook(body, sign(WEBHOOK_SECRET, clock.instant().getEpochSecond(), body));
    }

    protected ResultActions postWebhook(String body, String signature) throws Exception {
        MockHttpServletRequestBuilder request = json(post("/fake-provider/webhook"), body);
        if (signature != null) {
            request.header("X-Provider-Signature", signature);
        }
        return mockMvc.perform(request);
    }

    protected static String sign(String secret, long epochSeconds, String body) {
        return WebhookSignatures.sign(secret, epochSeconds, body.getBytes(StandardCharsets.UTF_8));
    }

    protected static String webhookJson(String eventId, String providerTransactionId, UUID paymentIntentId,
                                        String status, String amount, String currency) {
        return """
                {"providerEventId":"%s","providerTransactionId":"%s","paymentIntentId":"%s","status":"%s","amount":%s,"currency":"%s"}"""
                .formatted(eventId, providerTransactionId, paymentIntentId, status, amount, currency);
    }

    protected JsonNode body(ResultActions result) throws Exception {
        return objectMapper.readTree(result.andReturn().getResponse().getContentAsString());
    }

    // ---- concurrency ----------------------------------------------------------------------------

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
                results.add(future.get(60, TimeUnit.SECONDS));
            } catch (ExecutionException | TimeoutException ex) {
                results.add(ex);
            }
        }
        pool.shutdownNow();
        return results;
    }
}
