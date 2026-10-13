package id.fatarc.portfolio.payflowreliability.outbox;

import id.fatarc.portfolio.payflowreliability.common.BusinessException;
import id.fatarc.portfolio.payflowreliability.common.ErrorCode;
import id.fatarc.portfolio.payflowreliability.common.Hashing;
import id.fatarc.portfolio.payflowreliability.ledger.JournalType;
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

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The provider acted, but we crashed, lost the answer, or cannot record it: money must still be counted once. */
class OutboxRecoveryTest extends IntegrationTest {

    private OutboxCommand commandOf(RefundRequest refund) {
        return outboxCommandRepository.findByIdempotencyKey(OutboxCommand.refundKey(refund.getId())).orElseThrow();
    }

    /**
     * What a worker leaves behind when it dies right after the provider applied the refund: the dispatch
     * mark committed, the provider holding the refund under the command's key, nothing recorded here.
     */
    private void crashAfterProviderApplied(PaymentIntent intent, RefundRequest refund) {
        String providerTransactionId = transactionOf(intent).getProviderTransactionId();
        String requestHash = Hashing.sha256Hex("refund|" + providerTransactionId + "|"
                + refund.getAmount().stripTrailingZeros().toPlainString() + "|" + refund.getCurrency() + "|" + refund.getId());
        providerSimulator.refund(OutboxCommand.refundKey(refund.getId()), requestHash, providerTransactionId,
                refund.getAmount(), refund.getCurrency(), false);
        jdbcTemplate.update("""
                update outbox_commands set dispatched_at = ?, provider_transaction_id = ?, lease_owner = 'worker-dead',
                       lease_until = ? where id = ?""",
                Timestamp.from(clock.instant()), providerTransactionId,
                Timestamp.from(clock.instant().plus(Duration.ofSeconds(2))), commandOf(refund).getId());
    }

    @Test
    void shouldNeverCancelARefundTheProviderMayAlreadyHave() {
        PaymentIntent intent = paidIntent("ORDER-RC-1", "50000");
        RefundRequest refund = requestRefund(intent, "20000", "refund-rc-1");
        crashAfterProviderApplied(intent, refund);
        clock.advance(Duration.ofSeconds(3));

        assertThatThrownBy(() -> refundService.cancel(refund.getId(), AS_MERCHANT_A))
                .isInstanceOf(BusinessException.class)
                .extracting("code").isEqualTo(ErrorCode.REFUND_NOT_CANCELLABLE);

        drainOutbox();
        assertThat(reload(refund).getStatus()).isEqualTo(RefundStatus.COMPLETED);
        assertThat(simOperationRepository.count()).as("found by its key, not sent again").isEqualTo(1);
        assertThat(reload(intent).getRefundedAmount()).isEqualByComparingTo("20000");
    }

    @Test
    void shouldBookWhatTheProviderReversedWhileARefundIsStillOnItsWay() {
        PaymentIntent intent = paidIntent("ORDER-RC-2", "100000");
        RefundRequest refund = requestRefund(intent, "30000", "refund-rc-2");
        // The provider applied the refund; its worker died, so we do not know yet (lease held a little longer).
        crashAfterProviderApplied(intent, refund);
        providerSimulator.chargeback(intent.getId(), null);
        deliverWebhooks();
        assertThat(stateOf(intent)).isEqualTo(PaymentState.REVERSAL_REQUIRED);

        outboxWorker.runOnce();
        assertThat(reload(intent).getState()).isEqualTo(PaymentState.REVERSED);
        assertThat(reload(intent).getReversedAmount()).as("what the provider returned, not 100000").isEqualByComparingTo("70000");

        clock.advance(Duration.ofSeconds(3));
        drainOutbox();
        PaymentIntent settled = reload(intent);
        assertThat(reload(refund).getStatus()).isEqualTo(RefundStatus.COMPLETED);
        assertThat(settled.getRefundedAmount()).isEqualByComparingTo("30000");
        assertThat(settled.getReversedAmount().add(settled.getRefundedAmount())).isEqualByComparingTo(settled.getAmount());
        assertThat(ledgerJournalRepository.findByPaymentIntentIdOrderByCreatedAtAsc(intent.getId()))
                .extracting("journalType").containsExactly(JournalType.CAPTURE, JournalType.REVERSAL, JournalType.REFUND);
        assertThat(ledgerInvariantService.check().violations()).isEmpty();
    }

    @Test
    void shouldNotSendARefundForAPaymentThatWasReversedFirst() {
        PaymentIntent intent = paidIntent("ORDER-RC-3", "100000");
        RefundRequest refund = requestRefund(intent, "30000", "refund-rc-3");
        providerSimulator.chargeback(intent.getId(), null);
        deliverWebhooks();

        drainOutbox();

        assertThat(reload(refund).getStatus()).isEqualTo(RefundStatus.FAILED);
        assertThat(reload(refund).getFailureReason()).contains("reversed");
        assertThat(reload(intent).getReversedAmount()).isEqualByComparingTo("100000");
        assertThat(simOperationRepository.findAll()).extracting("operationType").containsExactly(
                id.fatarc.portfolio.payflowreliability.providersim.SimOperation.Type.REVERSAL);
    }

    @Test
    void shouldNotTreatAFailedLookupAsARefusal() {
        PaymentIntent intent = paidIntent("ORDER-RC-4", "50000");
        simFaults.inject(SimFaults.Operation.REFUND, SimFaults.Mode.TIMEOUT_AFTER_COMMIT, 1);
        RefundRequest refund = requestRefund(intent, "50000", "refund-rc-4");
        outboxWorker.runOnce();
        // The lookup is refused (as if our provider key were being rotated): unknown, not a refusal of the refund.
        simFaults.inject(SimFaults.Operation.LOOKUP, SimFaults.Mode.REJECT, 1);

        drainOutbox();

        assertThat(reload(refund).getStatus()).isEqualTo(RefundStatus.COMPLETED);
        assertThat(simOperationRepository.count()).isEqualTo(1);
        assertThat(stateOf(intent)).isEqualTo(PaymentState.REFUNDED);
    }

    @Test
    void shouldSendARefusedReversalAgainUnderANewKeyWhenAnOperatorDecides() {
        PaymentIntent intent = paidIntent("ORDER-RC-5", "40000");
        simFaults.inject(SimFaults.Operation.REVERSAL, SimFaults.Mode.REJECT, 1);
        providerSimulator.chargeback(intent.getId(), null);
        deliverWebhooks();
        drainOutbox();
        assertThat(stateOf(intent)).isEqualTo(PaymentState.REVERSAL_REQUIRED);
        ReviewCase reviewCase = openCases().getFirst();
        assertThat(reviewCase.getReason()).isEqualTo(ReviewReason.PROVIDER_REJECTED_OPERATION);
        assertThat(reviewWorkflowService.get(reviewCase.getId()).allowedActions()).containsExactly(ReviewAction.RETRY_PROVIDER_CALL);

        reviewWorkflowService.resolve(reviewCase.getId(),
                new ResolveReviewCaseRequest("Provider confirmed the refusal was an outage.", ReviewAction.RETRY_PROVIDER_CALL), OPS_ANA);
        drainOutbox();

        assertThat(stateOf(intent)).isEqualTo(PaymentState.REVERSED);
        assertThat(outboxCommandRepository.findByPaymentIntentIdOrderByCreatedAtAsc(intent.getId()))
                .extracting(OutboxCommand::getIdempotencyKey, OutboxCommand::getStatus)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple(OutboxCommand.reversalKey(intent.getId()), CommandStatus.FAILED),
                        org.assertj.core.groups.Tuple.tuple(OutboxCommand.reversalKey(intent.getId()) + "-2", CommandStatus.SUCCEEDED));
    }

    @Test
    void shouldEscalateAProviderAnswerThatCannotBeRecordedInsteadOfRetryingForever() {
        PaymentIntent intent = paidIntent("ORDER-RC-6", "100000");
        // Our records say 30000 was refunded; the provider knows of no refund and will reverse all 100000.
        jdbcTemplate.update("update payment_intents set refunded_amount = 30000 where id = ?", intent.getId());
        providerSimulator.chargeback(intent.getId(), null);
        deliverWebhooks();

        drainOutbox();

        OutboxCommand reversal = outboxCommandRepository.findByPaymentIntentIdOrderByCreatedAtAsc(intent.getId()).getFirst();
        assertThat(reversal.getStatus()).isEqualTo(CommandStatus.NEEDS_REVIEW);
        assertThat(reversal.getAttempts()).isEqualTo(3);
        assertThat(reversal.getLastError()).contains("recording it failed");
        assertThat(openReviewReasons()).containsExactly(ReviewReason.PROVIDER_OUTCOME_UNKNOWN);
        assertThat(stateOf(intent)).isEqualTo(PaymentState.REVERSAL_REQUIRED);
        assertThat(ledgerJournalRepository.findByPaymentIntentIdOrderByCreatedAtAsc(intent.getId()))
                .extracting("journalType").containsExactly(JournalType.CAPTURE);
    }
}
