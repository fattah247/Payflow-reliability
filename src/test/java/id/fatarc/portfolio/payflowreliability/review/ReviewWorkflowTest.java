package id.fatarc.portfolio.payflowreliability.review;

import id.fatarc.portfolio.payflowreliability.common.BusinessException;
import id.fatarc.portfolio.payflowreliability.common.ErrorCode;
import id.fatarc.portfolio.payflowreliability.ledger.JournalType;
import id.fatarc.portfolio.payflowreliability.outbox.CommandType;
import id.fatarc.portfolio.payflowreliability.payment.PaymentIntent;
import id.fatarc.portfolio.payflowreliability.payment.PaymentState;
import id.fatarc.portfolio.payflowreliability.payment.ProviderStatus;
import id.fatarc.portfolio.payflowreliability.refund.RefundRequest;
import id.fatarc.portfolio.payflowreliability.refund.RefundStatus;
import id.fatarc.portfolio.payflowreliability.review.dto.ResolveReviewCaseRequest;
import id.fatarc.portfolio.payflowreliability.review.dto.ReviewCaseView;
import id.fatarc.portfolio.payflowreliability.security.Caller;
import id.fatarc.portfolio.payflowreliability.support.IntegrationTest;
import org.junit.jupiter.api.Test;

import java.util.EnumSet;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ReviewWorkflowTest extends IntegrationTest {

    private ReviewCaseView resolve(UUID caseId, ReviewAction action) {
        return resolve(caseId, action, OPS_ANA);
    }

    private ReviewCaseView resolve(UUID caseId, ReviewAction action, Caller operator) {
        return reviewWorkflowService.resolve(caseId, new ResolveReviewCaseRequest("checked with provider", action), operator);
    }

    private ReviewCaseView onlyCaseFor(PaymentIntent intent) {
        return reviewWorkflowService.list(ReviewStatus.UNRESOLVED, null, intent.getId()).getFirst();
    }

    private PaymentIntent heldIntent(String reference) {
        PaymentIntent intent = startedIntent(reference, "60000");
        webhook(intent.getId(), "evt-" + reference, "tx-" + reference, ProviderStatus.SUCCESS, "59000");
        return reload(intent);
    }

    /** Paid out, then the provider asks for the money back: a contradiction that holds the payment. */
    private PaymentIntent chargedBackAfterPayout(String reference) {
        PaymentIntent intent = settledIntent(reference, "60000");
        providerSimulator.chargeback(intent.getId(), null);
        deliverWebhooks();
        return reload(intent);
    }

    @Test
    void shouldOfferHoldDecisionsAndNotAPlainAcknowledgeForAHeldPayment() {
        PaymentIntent intent = heldIntent("ORDER-5001");

        ReviewCaseView reviewCase = onlyCaseFor(intent);

        assertThat(reviewCase.paymentState()).isEqualTo(PaymentState.MANUAL_REVIEW);
        assertThat(reviewCase.allowedActions()).containsExactlyInAnyOrder(ReviewAction.RELEASE_PAYMENT, ReviewAction.FAIL_PAYMENT);
        assertThatThrownBy(() -> resolve(reviewCase.id(), ReviewAction.ACKNOWLEDGE))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("on hold")
                .extracting("code").isEqualTo(ErrorCode.RESOLUTION_ACTION_NOT_APPLICABLE);
        assertThat(stateOf(intent)).isEqualTo(PaymentState.MANUAL_REVIEW);
    }

    @Test
    void shouldReleaseAHeldPaymentToWhereItWasHeldFrom() {
        PaymentIntent intent = heldIntent("ORDER-5002");

        ReviewCaseView resolved = resolve(onlyCaseFor(intent).id(), ReviewAction.RELEASE_PAYMENT);

        assertThat(resolved.status()).isEqualTo(ReviewStatus.RESOLVED);
        assertThat(resolved.resolutionAction()).isEqualTo(ReviewAction.RELEASE_PAYMENT);
        assertThat(resolved.resolvedBy()).isEqualTo("operator:ops-ana");
        assertThat(resolved.allowedActions()).isEmpty();
        assertThat(stateOf(intent)).isEqualTo(PaymentState.PENDING);
        assertThat(auditFor(intent)).extracting("eventType").contains("MANUAL_REVIEW_RESOLVED");
    }

    @Test
    void shouldFailAHeldPaymentThatWasNeverCaptured() {
        PaymentIntent intent = heldIntent("ORDER-5003");

        resolve(onlyCaseFor(intent).id(), ReviewAction.FAIL_PAYMENT);

        assertThat(stateOf(intent)).isEqualTo(PaymentState.FAILED);
    }

    @Test
    void shouldNeedASecondOperatorToReverseAPaymentAfterPayout() {
        PaymentIntent intent = chargedBackAfterPayout("ORDER-5004");
        ReviewCaseView reviewCase = onlyCaseFor(intent);
        assertThat(reviewCase.paymentState()).isEqualTo(PaymentState.MANUAL_REVIEW);
        assertThat(reviewCase.allowedActions()).containsExactlyInAnyOrder(ReviewAction.RELEASE_PAYMENT, ReviewAction.REQUIRE_REVERSAL);
        assertThat(reviewCase.approvalRequiredFor()).containsExactly(ReviewAction.REQUIRE_REVERSAL);

        ReviewCaseView proposed = resolve(reviewCase.id(), ReviewAction.REQUIRE_REVERSAL, OPS_ANA);
        assertThat(proposed.status()).isEqualTo(ReviewStatus.AWAITING_APPROVAL);
        assertThat(proposed.proposedBy()).isEqualTo("operator:ops-ana");
        assertThat(stateOf(intent)).as("nothing happens on a proposal").isEqualTo(PaymentState.MANUAL_REVIEW);
        assertThat(outboxCommandRepository.count()).isZero();

        assertThatThrownBy(() -> reviewWorkflowService.approve(reviewCase.id(), "looks right", OPS_ANA))
                .isInstanceOf(BusinessException.class)
                .extracting("code").isEqualTo(ErrorCode.FOUR_EYES_REQUIRED);
        assertThatThrownBy(() -> resolve(reviewCase.id(), ReviewAction.RELEASE_PAYMENT, OPS_BUDI))
                .as("a proposal must be approved or rejected first")
                .isInstanceOf(BusinessException.class)
                .extracting("code").isEqualTo(ErrorCode.RESOLUTION_ACTION_NOT_APPLICABLE);

        ReviewCaseView approved = reviewWorkflowService.approve(reviewCase.id(), "Chargeback evidence verified.", OPS_BUDI);
        assertThat(approved.status()).isEqualTo(ReviewStatus.RESOLVED);
        assertThat(approved.approvedBy()).isEqualTo("operator:ops-budi");
        assertThat(stateOf(intent)).isEqualTo(PaymentState.REVERSAL_REQUIRED);
        assertThat(outboxCommandRepository.findAll()).singleElement().extracting("commandType").isEqualTo(CommandType.REVERSAL);

        drainOutbox();
        assertThat(reload(intent).getState()).isEqualTo(PaymentState.REVERSED);
        assertThat(reload(intent).getReversedAmount()).isEqualByComparingTo("60000");
        assertThat(ledgerJournalRepository.findByPaymentIntentIdOrderByCreatedAtAsc(intent.getId()))
                .extracting("journalType").containsExactly(JournalType.CAPTURE, JournalType.REVERSAL);
        assertThat(auditFor(intent)).extracting("eventType").contains("MANUAL_REVIEW_PROPOSED", "MANUAL_REVIEW_APPROVED");
    }

    @Test
    void shouldReopenACaseWhenAProposalIsRejected() {
        PaymentIntent intent = chargedBackAfterPayout("ORDER-5005");
        UUID caseId = onlyCaseFor(intent).id();
        resolve(caseId, ReviewAction.REQUIRE_REVERSAL, OPS_ANA);

        ReviewCaseView reopened = reviewWorkflowService.rejectProposal(caseId, "Chargeback was withdrawn.", OPS_BUDI);

        assertThat(reopened.status()).isEqualTo(ReviewStatus.OPEN);
        assertThat(reopened.proposedAction()).isNull();
        assertThat(reopened.allowedActions()).contains(ReviewAction.RELEASE_PAYMENT);
        assertThat(resolve(caseId, ReviewAction.RELEASE_PAYMENT, OPS_BUDI).status()).isEqualTo(ReviewStatus.RESOLVED);
        assertThat(stateOf(intent)).isEqualTo(PaymentState.SETTLED);
    }

    @Test
    void shouldApproveARefundOnAReconciledPaymentWithTwoOperatorsAndLetTheProviderExecuteIt() {
        PaymentIntent intent = reconciledIntent("ORDER-5006", "91000");
        RefundRequest refund = requestRefund(intent, "91000", "refund-5006");
        ReviewCaseView reviewCase = reviewWorkflowService.list(ReviewStatus.UNRESOLVED,
                ReviewReason.REFUND_BLOCKED_AFTER_RECONCILIATION, null).getFirst();
        assertThat(reviewCase.allowedActions()).containsExactlyInAnyOrder(ReviewAction.APPROVE_REFUND, ReviewAction.REJECT_REFUND);

        resolve(reviewCase.id(), ReviewAction.APPROVE_REFUND, OPS_ANA);
        assertThat(reload(refund).getStatus()).isEqualTo(RefundStatus.PENDING_REVIEW);
        reviewWorkflowService.approve(reviewCase.id(), "Damage confirmed by photos.", OPS_BUDI);
        assertThat(reload(refund).getStatus()).isEqualTo(RefundStatus.REQUESTED);

        drainOutbox();
        assertThat(reload(refund).getStatus()).isEqualTo(RefundStatus.COMPLETED);
        assertThat(stateOf(intent)).isEqualTo(PaymentState.REFUNDED);
    }

    @Test
    void shouldNotLetARefundCaseStandInForAHoldDecision() {
        PaymentIntent intent = reconciledIntent("ORDER-5013", "70000");
        RefundRequest refund = requestRefund(intent, "70000", "refund-5013");
        providerSimulator.chargeback(intent.getId(), null);
        deliverWebhooks();
        assertThat(stateOf(intent)).isEqualTo(PaymentState.MANUAL_REVIEW);

        ReviewCaseView hold = reviewWorkflowService.list(ReviewStatus.UNRESOLVED, ReviewReason.INVALID_STATE_TRANSITION,
                intent.getId()).getFirst();
        ReviewCaseView refundCase = reviewWorkflowService.list(ReviewStatus.UNRESOLVED,
                ReviewReason.REFUND_BLOCKED_AFTER_RECONCILIATION, intent.getId()).getFirst();

        assertThat(hold.allowedActions()).as("the refund case cannot release or reverse the payment")
                .doesNotContain(ReviewAction.ACKNOWLEDGE).contains(ReviewAction.RELEASE_PAYMENT);
        assertThat(refundCase.allowedActions()).as("no refund while the payment is held").containsExactly(ReviewAction.REJECT_REFUND);
        assertThatThrownBy(() -> resolve(refundCase.id(), ReviewAction.APPROVE_REFUND))
                .isInstanceOf(BusinessException.class)
                .extracting("code").isEqualTo(ErrorCode.RESOLUTION_ACTION_NOT_APPLICABLE);
        assertThat(reload(refund).getStatus()).isEqualTo(RefundStatus.PENDING_REVIEW);
    }

    @Test
    void shouldRejectARefundOnAReconciledPaymentWithOneOperator() {
        PaymentIntent intent = reconciledIntent("ORDER-5007", "91000");
        RefundRequest refund = requestRefund(intent, "91000", "refund-5007");

        resolve(openCases().getFirst().getId(), ReviewAction.REJECT_REFUND);

        assertThat(reload(refund).getStatus()).isEqualTo(RefundStatus.REJECTED);
        assertThat(stateOf(intent)).isEqualTo(PaymentState.RECONCILED);
        assertThat(outboxCommandRepository.count()).isZero();
    }

    @Test
    void shouldAcknowledgeACaseThatHoldsNothing() {
        PaymentIntent intent = startedIntent("ORDER-5008", "1000");
        webhook(intent.getId(), "evt-5008-failed", "tx-5008", ProviderStatus.FAILED, "1000");
        webhook(intent.getId(), "evt-5008-success", "tx-5008", ProviderStatus.SUCCESS, "1000");

        ReviewCaseView resolved = resolve(openCases().getFirst().getId(), null);

        assertThat(resolved.resolutionAction()).isEqualTo(ReviewAction.ACKNOWLEDGE);
        assertThat(stateOf(intent)).isEqualTo(PaymentState.FAILED);
    }

    @Test
    void shouldResolveCleanlyWhileTheProviderKeepsContradictingThePayment() throws Exception {
        PaymentIntent intent = startedIntent("ORDER-5009", "1000");
        webhook(intent.getId(), "evt-5009-failed", "tx-5009", ProviderStatus.FAILED, "1000");

        for (int round = 0; round < 5; round++) {
            webhook(intent.getId(), "evt-5009-open-" + round, "tx-5009", ProviderStatus.SUCCESS, "1000");
            UUID caseId = openCases().getFirst().getId();
            int currentRound = round;
            AtomicInteger turn = new AtomicInteger();

            List<Object> results = race(2, () -> turn.getAndIncrement() == 0
                    ? resolve(caseId, ReviewAction.ACKNOWLEDGE)
                    : webhook(intent.getId(), "evt-5009-race-" + currentRound, "tx-5009", ProviderStatus.SUCCESS, "1000"));

            assertThat(results).as("round %d", round).doesNotHaveAnyElementsOfTypes(Throwable.class);
            jdbcTemplate.update("update review_cases set status = 'RESOLVED', resolved_at = current_timestamp, resolved_by = 'test',"
                    + " resolution_action = 'ACKNOWLEDGE' where status = 'OPEN'");
        }
        assertThat(stateOf(intent)).isEqualTo(PaymentState.FAILED);
    }

    @Test
    void shouldRefuseToResolveACaseTwice() {
        PaymentIntent intent = heldIntent("ORDER-5010");
        UUID caseId = onlyCaseFor(intent).id();
        resolve(caseId, ReviewAction.RELEASE_PAYMENT);

        assertThatThrownBy(() -> resolve(caseId, ReviewAction.RELEASE_PAYMENT))
                .isInstanceOf(BusinessException.class)
                .extracting("code").isEqualTo(ErrorCode.REVIEW_CASE_ALREADY_RESOLVED);
    }

    @Test
    void shouldApplyOnlyOneDecisionWhenTwoOperatorsResolveConcurrently() throws Exception {
        PaymentIntent intent = heldIntent("ORDER-5011");
        UUID caseId = onlyCaseFor(intent).id();

        List<Object> results = race(2, () -> resolve(caseId, ReviewAction.RELEASE_PAYMENT));

        assertThat(results).filteredOn(ReviewCaseView.class::isInstance).hasSize(1);
        assertThat(results).filteredOn(BusinessException.class::isInstance)
                .singleElement().extracting("code").isEqualTo(ErrorCode.REVIEW_CASE_ALREADY_RESOLVED);
        assertThat(stateOf(intent)).isEqualTo(PaymentState.PENDING);
        assertThat(auditFor(intent)).filteredOn(event -> "MANUAL_REVIEW_RESOLVED".equals(event.getEventType())).hasSize(1);
    }

    @Test
    void shouldListAwaitingApprovalSeparately() {
        PaymentIntent intent = chargedBackAfterPayout("ORDER-5012");
        resolve(onlyCaseFor(intent).id(), ReviewAction.REQUIRE_REVERSAL, OPS_ANA);

        assertThat(reviewWorkflowService.list(EnumSet.of(ReviewStatus.AWAITING_APPROVAL), null, null)).hasSize(1);
        assertThat(reviewWorkflowService.list(EnumSet.of(ReviewStatus.OPEN), null, null)).isEmpty();
    }
}
