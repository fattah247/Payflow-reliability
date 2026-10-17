package id.fatarc.portfolio.payflowreliability.checkout;

import id.fatarc.portfolio.payflowreliability.common.BusinessException;
import id.fatarc.portfolio.payflowreliability.common.ErrorCode;
import id.fatarc.portfolio.payflowreliability.common.InvalidStateTransitionException;
import id.fatarc.portfolio.payflowreliability.ledger.JournalType;
import id.fatarc.portfolio.payflowreliability.operations.MoneyAtRiskService;
import id.fatarc.portfolio.payflowreliability.operations.OperationalMetrics;
import id.fatarc.portfolio.payflowreliability.payment.CancellationReason;
import id.fatarc.portfolio.payflowreliability.payment.PaymentIntent;
import id.fatarc.portfolio.payflowreliability.payment.PaymentState;
import id.fatarc.portfolio.payflowreliability.payment.ProviderStatus;
import id.fatarc.portfolio.payflowreliability.payment.WebhookOutcome;
import id.fatarc.portfolio.payflowreliability.review.ReviewReason;
import id.fatarc.portfolio.payflowreliability.support.IntegrationTest;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Slowness measured as money: how long customers wait for a confirmation, who gave up, and what is stuck. */
class CheckoutSloTest extends IntegrationTest {
    @Autowired
    private MeterRegistry meterRegistry;
    @Autowired
    private MoneyAtRiskService moneyAtRiskService;
    @Autowired
    private OperationalMetrics operationalMetrics;

    private double confirmations(boolean withinSlo) {
        return meterRegistry.counter("payflow.payment.confirmations", "within_slo", String.valueOf(withinSlo)).count();
    }

    @Test
    void shouldCountEachConfirmationAgainstTheSlo() {
        double goodBefore = confirmations(true);
        double badBefore = confirmations(false);

        PaymentIntent quick = startedIntent("ORDER-SLO-1", "1000");
        assertThat(reload(quick).getStartedAt()).isNotNull();
        webhook(quick.getId(), "evt-slo-1", "tx-slo-1", ProviderStatus.SUCCESS, "1000");

        PaymentIntent slow = startedIntent("ORDER-SLO-2", "2000");
        clock.advance(Duration.ofSeconds(25));
        webhook(slow.getId(), "evt-slo-2", "tx-slo-2", ProviderStatus.SUCCESS, "2000");

        assertThat(confirmations(true) - goodBefore).isEqualTo(1);
        assertThat(confirmations(false) - badBefore).as("25 s against a 10 s SLO").isEqualTo(1);
    }

    @Test
    void shouldShowPaymentsStuckPastTheSloAsMoneyAtRisk() {
        startedIntent("ORDER-SLO-3", "3000");
        startedIntent("ORDER-SLO-4", "4000");
        assertThat(moneyAtRiskService.compute().get(MoneyAtRiskService.Kind.PENDING_BEYOND_SLA).count()).isZero();

        clock.advance(Duration.ofSeconds(11));
        MoneyAtRiskService.Item pending = moneyAtRiskService.compute().get(MoneyAtRiskService.Kind.PENDING_BEYOND_SLA);

        assertThat(pending.count()).isEqualTo(2);
        assertThat(pending.amount().get("IDR")).isEqualByComparingTo("7000.00");
        operationalMetrics.refresh();
        assertThat(meterRegistry.get("payflow.money.at.risk.count").tag("kind", "pending_beyond_sla").gauge().value()).isEqualTo(2);
        assertThat(meterRegistry.get("payflow.money.at.risk.amount").tag("kind", "pending_beyond_sla").tag("currency", "IDR")
                .gauge().value()).isEqualTo(7000.0);
        assertThat(meterRegistry.get("payflow.health.up").gauge().value()).as("slow, but up").isEqualTo(1.0);
    }

    @Test
    void shouldCountAnAbandonedCheckoutAsMoneyLost() throws Exception {
        PaymentIntent intent = startedIntent("ORDER-SLO-5", "125000");

        mockMvc.perform(json(as(merchantAKey, post("/api/payment-intents/" + intent.getId() + "/cancel")), "{\"reason\": \"ABANDONED\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("CANCELLED"))
                .andExpect(jsonPath("$.cancellationReason").value("ABANDONED"));
        mockMvc.perform(json(as(merchantAKey, post("/api/payment-intents/" + intent.getId() + "/cancel")), "{\"reason\": \"ABANDONED\"}"))
                .andExpect(status().isOk());

        MoneyAtRiskService.Item abandoned = moneyAtRiskService.compute().get(MoneyAtRiskService.Kind.ABANDONED);
        assertThat(abandoned.count()).as("cancelling twice counts once").isEqualTo(1);
        assertThat(abandoned.amount().get("IDR")).isEqualByComparingTo("125000.00");
        assertThat(abandoned.perMinute().get("IDR")).as("over the 15-minute window").isEqualByComparingTo("8333.33");
        assertThat(meterRegistry.counter("payflow.checkout.abandoned.amount", "currency", "IDR").count()).isEqualTo(125000.0);
    }

    @Test
    void shouldRefuseToCancelAPaymentTheCustomerAlreadyPaid() {
        PaymentIntent captured = capturedIntent("ORDER-SLO-6", "1000");

        assertThatThrownBy(() -> paymentIntentService.cancel(captured.getId(), CancellationReason.ABANDONED, AS_MERCHANT_A))
                .isInstanceOf(InvalidStateTransitionException.class);
        assertThatThrownBy(() -> paymentIntentService.cancel(captured.getId(), CancellationReason.ABANDONED, AS_MERCHANT_B))
                .isInstanceOf(BusinessException.class).extracting("code").isEqualTo(ErrorCode.PAYMENT_INTENT_NOT_FOUND);
    }

    @Test
    void shouldSendACaptureForACancelledCheckoutToReviewInsteadOfTakingTheMoney() {
        PaymentIntent intent = startedIntent("ORDER-SLO-7", "1000");
        paymentIntentService.cancel(intent.getId(), CancellationReason.ABANDONED, AS_MERCHANT_A);

        // The customer paid after all, just as the shop gave up: the provider holds money for a cancelled payment.
        var result = webhook(intent.getId(), "evt-slo-7", "tx-slo-7", ProviderStatus.SUCCESS, "1000");

        assertThat(result.outcome()).isEqualTo(WebhookOutcome.SENT_TO_REVIEW);
        assertThat(result.reviewReason()).isEqualTo(ReviewReason.INVALID_STATE_TRANSITION);
        assertThat(stateOf(intent)).isEqualTo(PaymentState.CANCELLED);
        assertThat(ledgerJournalRepository.existsByJournalTypeAndReference(JournalType.CAPTURE, intent.getId().toString())).isFalse();
    }

    @Test
    void shouldCountRetriesAnsweredWithTheOriginalPayment() {
        double before = meterRegistry.counter("payflow.idempotency.replays", "operation", "payment-intents.create").count();
        createIntent("ORDER-SLO-8", "1000");
        createIntent("ORDER-SLO-8", "1000");
        createIntent("ORDER-SLO-8", "1000");

        assertThat(meterRegistry.counter("payflow.idempotency.replays", "operation", "payment-intents.create").count() - before)
                .isEqualTo(2);
    }
}
