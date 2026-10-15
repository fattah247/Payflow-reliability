package id.fatarc.portfolio.payflowreliability.providersim;

import id.fatarc.portfolio.payflowreliability.payment.PaymentIntent;
import id.fatarc.portfolio.payflowreliability.payment.PaymentState;
import id.fatarc.portfolio.payflowreliability.payment.ProviderEvent;
import id.fatarc.portfolio.payflowreliability.payment.WebhookOutcome;
import id.fatarc.portfolio.payflowreliability.review.ReviewAction;
import id.fatarc.portfolio.payflowreliability.review.dto.ResolveReviewCaseRequest;
import id.fatarc.portfolio.payflowreliability.support.IntegrationTest;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** The simulated provider behaves like a real one: signed, retried, sometimes duplicated webhooks; idempotent API. */
class ProviderSimulatorTest extends IntegrationTest {
    private static final String PROVIDER_KEY = "sim_test_provider_key";

    @Test
    void shouldDeliverDuplicatedWebhooksThatAreAppliedOnce() {
        PaymentIntent intent = startedIntent("ORDER-P-1", "10000");
        providerSimulator.pay(intent.getId(), new BigDecimal("10000"), "IDR",
                new ProviderSimulator.CustomerOptions(true, 3, null, true));

        deliverWebhooks();

        assertThat(stateOf(intent)).isEqualTo(PaymentState.SUCCESS);
        assertThat(providerSimulator.deliveries(intent.getId())).hasSize(6)
                .allSatisfy(delivery -> assertThat(delivery.getStatus()).isEqualTo(SimWebhookDelivery.Status.DELIVERED));
        // One inbox row per distinct event. Delivery order is not guaranteed (as with real providers): an
        // authorization that arrives after the capture is recorded as stale instead of applied.
        assertThat(providerEventRepository.findByPaymentIntentIdOrderByReceivedAtAsc(intent.getId()))
                .extracting(ProviderEvent::getOutcome)
                .containsAnyOf(WebhookOutcome.APPLIED)
                .doesNotContain(WebhookOutcome.SENT_TO_REVIEW, WebhookOutcome.DEFERRED)
                .hasSize(2);
        assertThat(ledgerJournalRepository.count()).isEqualTo(1);
        assertThat(openCases()).isEmpty();
    }

    @Test
    void shouldRedeliverAWebhookDeferredByAHoldOnceTheHoldIsResolved() {
        PaymentIntent intent = startedIntent("ORDER-P-2", "10000");
        // An earlier event with a wrong amount put the payment on hold.
        webhook(intent.getId(), "evt-p-2-wrong", "tx-p-2", id.fatarc.portfolio.payflowreliability.payment.ProviderStatus.SUCCESS, "1000");
        providerSimulator.pay(intent.getId(), new BigDecimal("10000"), "IDR", null);

        webhookDispatcher.runOnce();
        assertThat(stateOf(intent)).isEqualTo(PaymentState.MANUAL_REVIEW);
        assertThat(providerSimulator.deliveries(intent.getId())).singleElement().satisfies(delivery -> {
            assertThat(delivery.getStatus()).isEqualTo(SimWebhookDelivery.Status.PENDING);
            assertThat(delivery.getLastResponseStatus()).isEqualTo(409);
        });

        reviewWorkflowService.resolve(openCases().getFirst().getId(), new ResolveReviewCaseRequest(
                "Provider confirmed the authorization amount was a display bug.", ReviewAction.RELEASE_PAYMENT), OPS_ANA);
        deliverWebhooks();

        assertThat(stateOf(intent)).isEqualTo(PaymentState.SUCCESS);
        assertThat(providerSimulator.deliveries(intent.getId())).last()
                .satisfies(delivery -> assertThat(delivery.getStatus()).isEqualTo(SimWebhookDelivery.Status.DELIVERED));
    }

    @Test
    void shouldGiveUpAfterTheMaximumDeliveryAttempts() {
        PaymentIntent intent = startedIntent("ORDER-P-3", "10000");
        webhook(intent.getId(), "evt-p-3", "tx-p-3", id.fatarc.portfolio.payflowreliability.payment.ProviderStatus.SUCCESS, "9000");
        providerSimulator.pay(intent.getId(), new BigDecimal("10000"), "IDR", null);

        deliverWebhooks();

        assertThat(providerSimulator.deliveries(intent.getId())).singleElement().satisfies(delivery -> {
            assertThat(delivery.getStatus()).isEqualTo(SimWebhookDelivery.Status.FAILED);
            assertThat(delivery.getAttempts()).isEqualTo(3);
        });
    }

    @Test
    void shouldDeduplicateProviderCallsByIdempotencyKey() throws Exception {
        PaymentIntent intent = paidIntent("ORDER-P-4", "10000");
        String tx = transactionOf(intent).getProviderTransactionId();
        String call = """
                {"providerTransactionId": "%s", "amount": 4000, "currency": "IDR", "reference": "r-1"}""".formatted(tx);

        String first = mockMvc.perform(json(post("/provider-sim/v1/refunds"), call)
                        .header("Authorization", "Bearer " + PROVIDER_KEY).header("Idempotency-Key", "sim-key-1"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        String second = mockMvc.perform(json(post("/provider-sim/v1/refunds"), call.replace("4000", "4000.00"))
                        .header("Authorization", "Bearer " + PROVIDER_KEY).header("Idempotency-Key", "sim-key-1"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        mockMvc.perform(json(post("/provider-sim/v1/refunds"), call.replace("4000", "5000"))
                        .header("Authorization", "Bearer " + PROVIDER_KEY).header("Idempotency-Key", "sim-key-1"))
                .andExpect(status().isUnprocessableEntity());

        assertThat(second).isEqualTo(first);
        assertThat(providerSimulator.payment(intent.getId()).orElseThrow().getRefundedAmount()).isEqualByComparingTo("4000");
        mockMvc.perform(get("/provider-sim/v1/refunds/sim-key-1").header("Authorization", "Bearer " + PROVIDER_KEY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUCCEEDED"));
    }

    @Test
    void shouldForgetIdempotencyKeysAfterTheRetentionPeriodLikeARealProvider() {
        PaymentIntent intent = paidIntent("ORDER-P-KEY", "50000");
        String tx = transactionOf(intent).getProviderTransactionId();
        SimOperation first = providerSimulator.refund("key-p", "hash-p", tx, new BigDecimal("10000"), "IDR", false);

        assertThat(providerSimulator.refund("key-p", "hash-p", tx, new BigDecimal("10000"), "IDR", false).getId())
                .as("within the retention period the key is deduplicated").isEqualTo(first.getId());

        clock.advance(Duration.ofHours(25));
        assertThat(providerSimulator.find(SimOperation.Type.REFUND, "key-p")).as("the key is forgotten").isEmpty();
        SimOperation second = providerSimulator.refund("key-p", "hash-p", tx, new BigDecimal("10000"), "IDR", false);

        // Why the platform never resends a call first dispatched longer ago than this: it would be a new refund.
        assertThat(second.getId()).isNotEqualTo(first.getId());
        assertThat(providerSimulator.payment(intent.getId()).orElseThrow().getRefundedAmount()).isEqualByComparingTo("20000");
    }
}
