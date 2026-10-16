package id.fatarc.portfolio.payflowreliability.operations;

import id.fatarc.portfolio.payflowreliability.payment.PaymentIntent;
import id.fatarc.portfolio.payflowreliability.payment.ProviderStatus;
import id.fatarc.portfolio.payflowreliability.providersim.ProviderSimulator;
import id.fatarc.portfolio.payflowreliability.providersim.SimFaults;
import id.fatarc.portfolio.payflowreliability.support.IntegrationTest;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Operator views and the gauges alerting reads. */
class OperationsTest extends IntegrationTest {
    @Autowired
    private id.fatarc.portfolio.payflowreliability.ledger.LedgerService ledgerService;

    @Autowired
    private OperationalMetrics operationalMetrics;
    @Autowired
    private MeterRegistry meterRegistry;

    private double gauge(String name) {
        return meterRegistry.get(name).gauge().value();
    }

    @Test
    void shouldAssembleACaseFileWithEverythingAboutOnePayment() throws Exception {
        PaymentIntent intent = paidIntent("ORDER-OP-1", "5000");
        requestRefund(intent, "2000", "refund-op-1");
        drainOutbox();
        settle(MERCHANT_A);

        mockMvc.perform(as(OPS_ANA_KEY, get("/api/payment-intents/" + intent.getId() + "/case-file")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.payment.state").value("SETTLED"))
                .andExpect(jsonPath("$.payment.refundedAmount").value(2000.0))
                .andExpect(jsonPath("$.attention.needsAction").value(false))
                .andExpect(jsonPath("$.providerEvents[0].source").value("WEBHOOK"))
                .andExpect(jsonPath("$.providerCalls[0].status").value("SUCCEEDED"))
                .andExpect(jsonPath("$.refunds[0].status").value("COMPLETED"))
                .andExpect(jsonPath("$.settlementItems[*].itemType", containsInAnyOrder("CAPTURE", "REFUND")))
                .andExpect(jsonPath("$.ledger[*].type", contains("CAPTURE", "REFUND")))
                .andExpect(jsonPath("$.auditChain.valid").value(true))
                .andExpect(jsonPath("$.timeline[*].eventType", hasItems("PAYMENT_INTENT_CREATED", "REFUND_COMPLETED")));
    }

    @Test
    void shouldExplainWhatAHeldPaymentIsWaitingOn() throws Exception {
        PaymentIntent intent = startedIntent("ORDER-OP-2", "5000");
        webhook(intent.getId(), "evt-op-2-auth", "tx-op-2", ProviderStatus.AUTHORIZED, "5000");
        webhook(intent.getId(), "evt-op-2-wrong", "tx-op-2", ProviderStatus.SUCCESS, "4000");

        mockMvc.perform(as(OPS_ANA_KEY, get("/api/payment-intents/" + intent.getId() + "/case-file")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.payment.state").value("MANUAL_REVIEW"))
                .andExpect(jsonPath("$.attention.needsAction").value(true))
                .andExpect(jsonPath("$.attention.waitingOn", containsString("held from AUTHORIZED")))
                .andExpect(jsonPath("$.attention.openReviewCaseIds", hasSize(1)))
                .andExpect(jsonPath("$.providerEvents[*].outcome", contains("APPLIED", "SENT_TO_REVIEW")))
                .andExpect(jsonPath("$.reviewCases[0].allowedActions", hasItem("REQUIRE_REVERSAL")))
                .andExpect(jsonPath("$.reviewCases[0].approvalRequiredFor", contains("REQUIRE_REVERSAL")));
        mockMvc.perform(as(merchantAKey, get("/api/payment-intents/" + intent.getId() + "/case-file")))
                .andExpect(status().isForbidden());
    }

    @Test
    void shouldSummarizeWhatNeedsAttention() throws Exception {
        capturedIntent("ORDER-OP-3", "1000");
        capturedIntent("ORDER-OP-4", "2500");
        PaymentIntent held = startedIntent("ORDER-OP-5", "1000");
        webhook(held.getId(), "evt-op-5", "tx-op-5", ProviderStatus.SUCCESS, "1");
        ledgerInvariantService.check();

        mockMvc.perform(as(OPS_ANA_KEY, get("/api/operations/summary")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.paymentsByState.SUCCESS").value(2))
                .andExpect(jsonPath("$.paymentsByState.REFUNDED").value(0))
                .andExpect(jsonPath("$.reviewQueue.unresolved").value(1))
                .andExpect(jsonPath("$.reviewQueue.unresolvedByReason.AMOUNT_MISMATCH").value(1))
                .andExpect(jsonPath("$.reviewQueue.paymentsOnHold").value(1))
                .andExpect(jsonPath("$.reviewQueue.oldestOpenedAt").isNotEmpty())
                .andExpect(jsonPath("$.providerCalls.needsReview").value(0))
                .andExpect(jsonPath("$.awaitingSettlement[0].merchantId").value(MERCHANT_A))
                .andExpect(jsonPath("$.awaitingSettlement[0].paymentCount").value(2))
                .andExpect(jsonPath("$.awaitingSettlement[0].netAmount").value(3500.0))
                .andExpect(jsonPath("$.ledger.healthy").value(true));
    }

    @Test
    void shouldLetAnOperatorTriggerAStatusSweep() throws Exception {
        PaymentIntent intent = startedIntent("ORDER-OP-6", "5000");
        providerSimulator.pay(intent.getId(), new BigDecimal("5000"), "IDR",
                new ProviderSimulator.CustomerOptions(false, 1, null, false));

        mockMvc.perform(as(OPS_ANA_KEY, post("/api/operations/sweeps/payment-status?olderThanSeconds=0")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.checked").value(1))
                .andExpect(jsonPath("$.recovered").value(1));
    }

    @Test
    void shouldPublishGaugesForAlerting() {
        PaymentIntent stuck = startedIntent("ORDER-OP-7", "5000");
        PaymentIntent refunded = paidIntent("ORDER-OP-8", "5000");
        simFaults.inject(SimFaults.Operation.REFUND, SimFaults.Mode.UNAVAILABLE, 10);
        requestRefund(refunded, "5000", "refund-op-8");
        drainOutbox();
        clock.advance(Duration.ofMinutes(20));
        ledgerInvariantService.check();

        operationalMetrics.refresh();

        assertThat(gauge("payflow.outbox.needs.review")).isEqualTo(1);
        assertThat(gauge("payflow.review.queue.unresolved")).isEqualTo(1);
        assertThat(gauge("payflow.review.queue.oldest.age")).isGreaterThanOrEqualTo(Duration.ofMinutes(20).toSeconds());
        assertThat(gauge("payflow.payments.stuck")).as("payment %s waits on the provider", stuck.getId()).isEqualTo(1);
        assertThat(gauge("payflow.ledger.invariant.violations")).isZero();
        assertThat(gauge("payflow.ledger.invariant.check.age")).isLessThan(5);
        assertThat(gauge("payflow.metrics.snapshot.age")).isLessThan(5);
    }

    @Test
    void shouldCheckEveryMoneyRuleOnDemandWithTheEvidence() throws Exception {
        paidIntent("ORDER-OPS-INV", "3000");
        settle(MERCHANT_A);
        landPayouts();

        mockMvc.perform(as(OPS_ANA_KEY, get("/api/operations/invariants")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.allHold").value(true))
                .andExpect(jsonPath("$.rules[*].name", hasItems("BOOKS_BALANCE", "CAPTURE_PAID_OUT_AT_MOST_ONCE",
                        "RECONCILED_ONLY_WITH_A_BANK_LINE", "UNMATCHED_MONEY_HAS_A_BREAK", "BANK_ENTRY_BOOKED_ONCE",
                        "LANDING_IS_PROVEN", "BLOCKED_NEVER_REACHES_THE_PROVIDER", "STEP_UP_CONFIRMED_BEFORE_CAPTURE",
                        "NO_RETRY_CHARGES_TWICE", "REFUNDED_ONCE", "PAYOUT_SENT_ONCE")))
                .andExpect(jsonPath("$.rules[0].violations", empty()));

        // Money that no bank line explains, booked around the import: the books no longer agree with the bank.
        new org.springframework.transaction.support.TransactionTemplate(transactionManager).executeWithoutResult(status ->
                ledgerService.bankUnmatched(java.util.UUID.randomUUID(), "IDR", new BigDecimal("100")));

        mockMvc.perform(as(OPS_ANA_KEY, get("/api/operations/invariants")))
                .andExpect(jsonPath("$.allHold").value(false))
                .andExpect(jsonPath("$.rules[0].holds").value(false))
                .andExpect(jsonPath("$.rules[0].violations[0]", containsString("BANK_CASH_MISMATCH")));
    }
}
