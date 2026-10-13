package id.fatarc.portfolio.payflowreliability.payment;

import id.fatarc.portfolio.payflowreliability.providersim.ProviderSimulator;
import id.fatarc.portfolio.payflowreliability.providersim.SimFaults;
import id.fatarc.portfolio.payflowreliability.providersim.SimWebhookDelivery;
import id.fatarc.portfolio.payflowreliability.review.ReviewReason;
import id.fatarc.portfolio.payflowreliability.support.IntegrationTest;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/** Lost webhooks are recovered by asking the provider; the answer goes through the same rules as a webhook. */
class PaymentStatusSweeperTest extends IntegrationTest {
    private static final ProviderSimulator.CustomerOptions LOST_WEBHOOK =
            new ProviderSimulator.CustomerOptions(false, 1, null, false);

    @Test
    void shouldRecoverAPaymentWhoseWebhookWasLost() {
        PaymentIntent intent = startedIntent("ORDER-S-1", "75000");
        providerSimulator.pay(intent.getId(), new BigDecimal("75000"), "IDR", LOST_WEBHOOK);
        deliverWebhooks();
        assertThat(stateOf(intent)).isEqualTo(PaymentState.PENDING);
        assertThat(providerSimulator.deliveries(intent.getId())).extracting(SimWebhookDelivery::getStatus)
                .containsExactly(SimWebhookDelivery.Status.DROPPED);

        assertThat(sweeper.runOnce().checked()).as("not stuck yet").isZero();
        clock.advance(Duration.ofMinutes(16));
        PaymentStatusSweeper.SweepReport report = sweeper.runOnce();

        assertThat(report.recovered()).isEqualTo(1);
        assertThat(stateOf(intent)).isEqualTo(PaymentState.SUCCESS);
        assertThat(providerEventRepository.findByPaymentIntentIdOrderByReceivedAtAsc(intent.getId()))
                .singleElement().extracting(ProviderEvent::getSource).isEqualTo(EventSource.POLL);
        assertThat(ledgerJournalRepository.findByPaymentIntentIdOrderByCreatedAtAsc(intent.getId())).hasSize(1);
    }

    @Test
    void shouldTreatTheLateWebhookAfterARecoveryAsADuplicate() {
        PaymentIntent intent = startedIntent("ORDER-S-2", "75000");
        providerSimulator.pay(intent.getId(), new BigDecimal("75000"), "IDR", null);
        sweeper.runOnce(Duration.ZERO);
        assertThat(stateOf(intent)).isEqualTo(PaymentState.SUCCESS);

        deliverWebhooks();

        assertThat(providerEventRepository.findByPaymentIntentIdOrderByReceivedAtAsc(intent.getId()))
                .extracting(ProviderEvent::getOutcome).containsExactly(WebhookOutcome.APPLIED, WebhookOutcome.DUPLICATE_IGNORED);
        assertThat(ledgerJournalRepository.count()).isEqualTo(1);
    }

    @Test
    void shouldNotAskAgainBeforeTheRecheckInterval() {
        PaymentIntent intent = startedIntent("ORDER-S-3", "75000");

        assertThat(sweeper.runOnce(Duration.ZERO).checked()).isEqualTo(1);
        assertThat(sweeper.runOnce(Duration.ZERO).checked()).isZero();
        clock.advance(Duration.ofMinutes(16));
        assertThat(sweeper.runOnce(Duration.ZERO).checked()).isEqualTo(1);
        assertThat(stateOf(intent)).isEqualTo(PaymentState.PENDING);
    }

    @Test
    void shouldExpireAPaymentTheProviderNeverHeardOfAfterTheGracePeriod() {
        PaymentIntent unknownToProvider = startedIntent("ORDER-S-4", "75000");
        PaymentIntent neverStarted = createIntent("ORDER-S-5", "75000");

        clock.advance(Duration.ofHours(23));
        sweeper.runOnce();
        assertThat(stateOf(unknownToProvider)).isEqualTo(PaymentState.PENDING);
        assertThat(stateOf(neverStarted)).isEqualTo(PaymentState.CREATED);

        clock.advance(Duration.ofHours(2));
        PaymentStatusSweeper.SweepReport report = sweeper.runOnce();

        assertThat(report.expired()).isEqualTo(2);
        assertThat(stateOf(unknownToProvider)).isEqualTo(PaymentState.EXPIRED);
        assertThat(stateOf(neverStarted)).isEqualTo(PaymentState.EXPIRED);
        assertThat(auditFor(unknownToProvider)).last()
                .satisfies(event -> assertThat(event.getActor()).isEqualTo("system:status-sweeper"));
    }

    @Test
    void shouldLeavePaymentsAloneWhenTheProviderCannotBeAsked() {
        PaymentIntent intent = startedIntent("ORDER-S-6", "75000");
        providerSimulator.pay(intent.getId(), new BigDecimal("75000"), "IDR", LOST_WEBHOOK);
        simFaults.inject(SimFaults.Operation.STATUS, SimFaults.Mode.UNAVAILABLE, 1);

        PaymentStatusSweeper.SweepReport report = sweeper.runOnce(Duration.ZERO);

        assertThat(report.errors()).isEqualTo(1);
        assertThat(stateOf(intent)).isEqualTo(PaymentState.PENDING);
        assertThat(sweeper.runOnce(Duration.ZERO).recovered()).as("the next sweep tries again").isEqualTo(1);
    }

    @Test
    void shouldSendAPolledAnswerThatDisagreesToReview() {
        PaymentIntent intent = startedIntent("ORDER-S-7", "75000");
        providerSimulator.pay(intent.getId(), new BigDecimal("70000"), "IDR", LOST_WEBHOOK);

        PaymentStatusSweeper.SweepReport report = sweeper.runOnce(Duration.ZERO);

        assertThat(report.sentToReview()).isEqualTo(1);
        assertThat(stateOf(intent)).isEqualTo(PaymentState.MANUAL_REVIEW);
        assertThat(openReviewReasons()).containsExactly(ReviewReason.AMOUNT_MISMATCH);
    }
}
