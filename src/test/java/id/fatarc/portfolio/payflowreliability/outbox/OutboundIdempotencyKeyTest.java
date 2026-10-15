package id.fatarc.portfolio.payflowreliability.outbox;

import id.fatarc.portfolio.payflowreliability.ledger.JournalType;
import id.fatarc.portfolio.payflowreliability.payment.PaymentIntent;
import id.fatarc.portfolio.payflowreliability.providersim.SimApiRequest;
import id.fatarc.portfolio.payflowreliability.providersim.SimFaults;
import id.fatarc.portfolio.payflowreliability.refund.RefundRequest;
import id.fatarc.portfolio.payflowreliability.refund.RefundStatus;
import id.fatarc.portfolio.payflowreliability.review.ReviewReason;
import id.fatarc.portfolio.payflowreliability.support.CrashableProviderClient;
import id.fatarc.portfolio.payflowreliability.support.IntegrationTest;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Keys on calls this service makes to the provider, seen from the provider's side (its request log).
 * The key is derived once from the business operation when the command is created, never per attempt.
 */
class OutboundIdempotencyKeyTest extends IntegrationTest {

    private List<SimApiRequest> providerRequestsFor(RefundRequest refund) {
        return simApiRequestRepository.findByOperationOrderByReceivedAtAsc("REFUND").stream()
                .filter(request -> request.getIdempotencyKey().contains(refund.getId().toString()))
                .toList();
    }

    private OutboxCommand commandOf(RefundRequest refund) {
        return outboxCommandRepository.findByIdempotencyKey(OutboxCommand.refundKey(refund.getId())).orElseThrow();
    }

    @Test
    void shouldDeriveTheKeyFromTheOperationWhenTheCommandIsCreated() {
        PaymentIntent intent = paidIntent("ORDER-OK-1", "50000");
        RefundRequest refund = requestRefund(intent, "20000", "client-refund-key");

        assertThat(commandOf(refund).getIdempotencyKey())
                .as("our key, derived from the refund; not the client's key and not random")
                .isEqualTo("refund-" + refund.getId());
    }

    @Test
    void shouldSendEveryAttemptUnderTheSameKey() {
        PaymentIntent intent = paidIntent("ORDER-OK-2", "50000");
        simFaults.inject(SimFaults.Operation.REFUND, SimFaults.Mode.UNAVAILABLE, 2);
        RefundRequest refund = requestRefund(intent, "20000", "refund-ok-2");

        drainOutbox();

        assertThat(reload(refund).getStatus()).isEqualTo(RefundStatus.COMPLETED);
        assertThat(providerRequestsFor(refund))
                .extracting(SimApiRequest::getIdempotencyKey, SimApiRequest::getOutcome)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple("refund-" + refund.getId(), SimApiRequest.Outcome.UNAVAILABLE),
                        org.assertj.core.groups.Tuple.tuple("refund-" + refund.getId(), SimApiRequest.Outcome.UNAVAILABLE),
                        org.assertj.core.groups.Tuple.tuple("refund-" + refund.getId(), SimApiRequest.Outcome.APPLIED));
    }

    @Test
    void shouldResendTheSameKeyAfterAWorkerDiesOnceTheProviderActed() {
        PaymentIntent intent = paidIntent("ORDER-OK-3", "50000");
        RefundRequest refund = requestRefund(intent, "20000", "refund-ok-3");

        // The worker dispatches, the provider refunds the customer, and the worker dies before recording it.
        providerClient.crashAfterNextCall();
        assertThatThrownBy(() -> outboxWorker.runOnce()).isInstanceOf(CrashableProviderClient.SimulatedCrash.class);
        assertThat(reload(refund).getStatus()).as("nothing recorded here").isEqualTo(RefundStatus.REQUESTED);
        assertThat(commandOf(refund).wasDispatched()).as("but dispatch was committed before the call left").isTrue();
        assertThat(providerSimulator.payment(intent.getId()).orElseThrow().getRefundedAmount()).isEqualByComparingTo("20000");

        // Another worker picks the command up once the dead one's lease expires.
        clock.advance(Duration.ofSeconds(3));
        drainOutbox();

        assertThat(reload(refund).getStatus()).isEqualTo(RefundStatus.COMPLETED);
        assertThat(providerSimulator.payment(intent.getId()).orElseThrow().getRefundedAmount())
                .as("the customer is refunded once, not twice").isEqualByComparingTo("20000");
        assertThat(providerRequestsFor(refund)).extracting(SimApiRequest::getIdempotencyKey)
                .containsOnly("refund-" + refund.getId());
        assertThat(providerRequestsFor(refund)).filteredOn(request -> request.getOutcome() == SimApiRequest.Outcome.APPLIED)
                .hasSize(1);
        assertThat(ledgerJournalRepository.existsByJournalTypeAndReference(JournalType.REFUND, refund.getId().toString())).isTrue();
        assertThat(reload(intent).getRefundedAmount()).isEqualByComparingTo("20000");
    }

    @Test
    void shouldNeverResendUnderAKeyTheProviderMayHaveForgotten() {
        PaymentIntent intent = paidIntent("ORDER-OK-4", "50000");
        RefundRequest refund = requestRefund(intent, "20000", "refund-ok-4");
        providerClient.crashAfterNextCall();
        assertThatThrownBy(() -> outboxWorker.runOnce()).isInstanceOf(CrashableProviderClient.SimulatedCrash.class);

        // Nobody looks at the command for a day. The provider prunes idempotency keys after 24 hours, so it
        // now answers "never heard of refund-<id>": the same answer as for a call that never arrived.
        clock.advance(Duration.ofHours(25));
        drainOutbox();

        OutboxCommand command = commandOf(refund);
        assertThat(command.getStatus()).isEqualTo(CommandStatus.NEEDS_REVIEW);
        assertThat(command.getLastError()).contains("longer ago than the provider keeps idempotency keys");
        assertThat(openReviewReasons()).containsExactly(ReviewReason.PROVIDER_OUTCOME_UNKNOWN);
        assertThat(reload(refund).getStatus()).as("still reserved, never assumed").isEqualTo(RefundStatus.REQUESTED);
        assertThat(providerSimulator.payment(intent.getId()).orElseThrow().getRefundedAmount())
                .as("a resend would have been a second refund").isEqualByComparingTo("20000");
        assertThat(providerRequestsFor(refund)).hasSize(1);
    }
}
