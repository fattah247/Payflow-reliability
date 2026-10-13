package id.fatarc.portfolio.payflowreliability.outbox;

import id.fatarc.portfolio.payflowreliability.payment.PaymentIntent;
import id.fatarc.portfolio.payflowreliability.payment.PaymentState;
import id.fatarc.portfolio.payflowreliability.providersim.SimFaults;
import id.fatarc.portfolio.payflowreliability.refund.RefundRequest;
import id.fatarc.portfolio.payflowreliability.refund.RefundStatus;
import id.fatarc.portfolio.payflowreliability.review.ReviewAction;
import id.fatarc.portfolio.payflowreliability.review.ReviewCase;
import id.fatarc.portfolio.payflowreliability.review.ReviewReason;
import id.fatarc.portfolio.payflowreliability.review.dto.ResolveReviewCaseRequest;
import id.fatarc.portfolio.payflowreliability.support.IntegrationTest;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Provider calls with unknown outcomes: retried safely by idempotency key, escalated when retries run out. */
class OutboxTest extends IntegrationTest {

    private OutboxCommand commandFor(PaymentIntent intent) {
        return outboxCommandRepository.findByPaymentIntentIdOrderByCreatedAtAsc(intent.getId()).getFirst();
    }

    @Test
    void shouldRetryAnOutageAndSucceedWithTheSameIdempotencyKey() {
        PaymentIntent intent = paidIntent("ORDER-O-1", "50000");
        simFaults.inject(SimFaults.Operation.REFUND, SimFaults.Mode.UNAVAILABLE, 1);
        RefundRequest refund = requestRefund(intent, "50000", "refund-o-1");

        assertThat(outboxWorker.runOnce()).isEqualTo(1);
        OutboxCommand afterOutage = commandFor(intent);
        assertThat(afterOutage.getStatus()).isEqualTo(CommandStatus.PENDING);
        assertThat(afterOutage.getAttempts()).isEqualTo(1);
        assertThat(afterOutage.getLastError()).contains("503");
        assertThat(afterOutage.getNextAttemptAt()).isAfter(afterOutage.getUpdatedAt().minusMillis(1));

        drainOutbox();
        assertThat(commandFor(intent).getStatus()).isEqualTo(CommandStatus.SUCCEEDED);
        assertThat(commandFor(intent).getAttempts()).isEqualTo(2);
        assertThat(reload(refund).getStatus()).isEqualTo(RefundStatus.COMPLETED);
    }

    @Test
    void shouldLookUpAnUnknownOutcomeInsteadOfRefundingTwice() {
        PaymentIntent intent = paidIntent("ORDER-O-2", "50000");
        // The provider commits the refund but answers after our read timeout.
        simFaults.inject(SimFaults.Operation.REFUND, SimFaults.Mode.TIMEOUT_AFTER_COMMIT, 1);
        RefundRequest refund = requestRefund(intent, "50000", "refund-o-2");

        outboxWorker.runOnce();
        assertThat(reload(refund).getStatus()).as("we do not know yet").isEqualTo(RefundStatus.REQUESTED);
        assertThat(commandFor(intent).getLastError()).contains("No response");
        assertThat(simOperationRepository.count()).as("but the provider already did it").isEqualTo(1);

        drainOutbox();
        assertThat(reload(refund).getStatus()).isEqualTo(RefundStatus.COMPLETED);
        assertThat(simOperationRepository.count()).isEqualTo(1);
        assertThat(providerSimulator.payment(intent.getId()).orElseThrow().getRefundedAmount()).isEqualByComparingTo("50000");
        assertThat(reload(intent).getRefundedAmount()).isEqualByComparingTo("50000");
    }

    @Test
    void shouldEscalateWhenRetriesRunOutAndRetryAfterAnOperatorDecides() {
        PaymentIntent intent = paidIntent("ORDER-O-3", "50000");
        simFaults.inject(SimFaults.Operation.REFUND, SimFaults.Mode.UNAVAILABLE, 10);
        RefundRequest refund = requestRefund(intent, "50000", "refund-o-3");

        drainOutbox();

        assertThat(commandFor(intent).getStatus()).isEqualTo(CommandStatus.NEEDS_REVIEW);
        assertThat(commandFor(intent).getAttempts()).isEqualTo(3);
        assertThat(reload(refund).getStatus()).as("money may have moved; the refund stays in progress").isEqualTo(RefundStatus.REQUESTED);
        ReviewCase reviewCase = openCases().getFirst();
        assertThat(reviewCase.getReason()).isEqualTo(ReviewReason.PROVIDER_OUTCOME_UNKNOWN);
        assertThat(reviewWorkflowService.get(reviewCase.getId()).allowedActions()).containsExactly(ReviewAction.RETRY_PROVIDER_CALL);

        simFaults.clear();
        reviewWorkflowService.resolve(reviewCase.getId(),
                new ResolveReviewCaseRequest("Provider status page shows the outage is over.", ReviewAction.RETRY_PROVIDER_CALL), OPS_ANA);
        drainOutbox();

        assertThat(commandFor(intent).getStatus()).isEqualTo(CommandStatus.SUCCEEDED);
        assertThat(reload(refund).getStatus()).isEqualTo(RefundStatus.COMPLETED);
        assertThat(openCases()).isEmpty();
    }

    @Test
    void shouldLetOnlyOneWorkerHoldACommand() throws Exception {
        PaymentIntent intent = paidIntent("ORDER-O-4", "50000");
        requestRefund(intent, "50000", "refund-o-4");

        List<Object> claimed = race(4, () -> outboxWorker.runOnce());

        assertThat(claimed).doesNotHaveAnyElementsOfTypes(Throwable.class);
        assertThat(claimed.stream().mapToInt(result -> (Integer) result).sum()).isEqualTo(1);
        assertThat(simOperationRepository.count()).isEqualTo(1);
        assertThat(stateOf(intent)).isEqualTo(PaymentState.REFUNDED);
    }

    @Test
    void shouldPickUpACommandWhoseWorkerDiedAfterTheLeaseExpires() {
        PaymentIntent intent = paidIntent("ORDER-O-5", "50000");
        requestRefund(intent, "50000", "refund-o-5");
        OutboxCommand command = commandFor(intent);
        // A worker on another instance claimed the command and crashed before recording anything.
        outboxCommandRepository.findById(command.getId()).ifPresent(ignored -> jdbcTemplate.update(
                "update outbox_commands set lease_owner = 'worker-dead', lease_until = ? where id = ?",
                java.sql.Timestamp.from(clock.instant().plus(Duration.ofSeconds(2))), command.getId()));

        assertThat(outboxWorker.runOnce()).as("still leased").isZero();
        clock.advance(Duration.ofSeconds(3));
        assertThat(outboxWorker.runOnce()).isEqualTo(1);
        assertThat(commandFor(intent).getStatus()).isEqualTo(CommandStatus.SUCCEEDED);
    }

    @Test
    void shouldReverseThroughTheProviderAndReturnOnlyWhatWasNotRefunded() {
        PaymentIntent intent = paidIntent("ORDER-O-6", "50000");
        requestRefund(intent, "20000", "refund-o-6");
        drainOutbox();

        providerSimulator.chargeback(intent.getId(), null);
        deliverWebhooks();
        assertThat(stateOf(intent)).isEqualTo(PaymentState.REVERSAL_REQUIRED);
        drainOutbox();

        PaymentIntent reversed = reload(intent);
        assertThat(reversed.getState()).isEqualTo(PaymentState.REVERSED);
        assertThat(reversed.getReversedAmount()).isEqualByComparingTo("30000");
        assertThat(ledgerQueryService.balances(MERCHANT_A, "IDR")).singleElement()
                .satisfies(balance -> assertThat(balance.owedToMerchant()).isEqualByComparingTo("0"));
    }

    @Test
    void shouldSpreadRetriesWithCappedExponentialBackoff() {
        assertThat(outboxWorker.backoff(1)).isBetween(Duration.ZERO, Duration.ofMillis(1));
        for (int attempt = 1; attempt < 40; attempt++) {
            assertThat(outboxWorker.backoff(attempt)).isLessThanOrEqualTo(Duration.ofMillis(2));
        }
    }
}
