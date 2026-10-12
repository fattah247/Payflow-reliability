package id.fatarc.portfolio.payflowreliability.payment;

import id.fatarc.portfolio.payflowreliability.common.InvalidStateTransitionException;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Pure domain rules, no Spring context. */
class PaymentIntentStateMachineTest {

    private static PaymentIntent intent() {
        return new PaymentIntent("merchant", "ORDER-1", new BigDecimal("100.00"), "IDR", "key");
    }

    @Test
    void shouldFollowTheCapturePathToReconciliation() {
        PaymentIntent intent = intent();
        intent.start();
        intent.authorize();
        intent.succeed();
        intent.settle();
        intent.reconcile();
        assertThat(intent.getState()).isEqualTo(PaymentState.RECONCILED);
    }

    @Test
    void shouldRejectRegressionFromSuccessToFailed() {
        PaymentIntent intent = intent();
        intent.start();
        intent.succeed();
        assertThatThrownBy(intent::fail)
                .isInstanceOf(InvalidStateTransitionException.class)
                .hasMessageContaining("Current state: SUCCESS");
    }

    @Test
    void shouldRememberWhereAPaymentWasHeldFromAndReleaseItThere() {
        PaymentIntent intent = intent();
        intent.start();
        intent.authorize();

        assertThat(intent.holdForReview()).isEqualTo(PaymentState.AUTHORIZED);
        assertThat(intent.getState()).isEqualTo(PaymentState.MANUAL_REVIEW);
        assertThat(intent.getStateBeforeReview()).isEqualTo(PaymentState.AUTHORIZED);

        intent.releaseFromReview();
        assertThat(intent.getState()).isEqualTo(PaymentState.AUTHORIZED);
        assertThat(intent.getStateBeforeReview()).isNull();
    }

    @Test
    void shouldNeverHoldAFinalOutcome() {
        PaymentIntent intent = intent();
        intent.start();
        intent.fail();

        assertThat(intent.canBeHeldForReview()).isFalse();
        assertThatThrownBy(intent::holdForReview).isInstanceOf(InvalidStateTransitionException.class);
        assertThat(intent.getState()).isEqualTo(PaymentState.FAILED);
    }

    @Test
    void shouldOnlyFailAHeldPaymentThatWasNeverCaptured() {
        PaymentIntent captured = intent();
        captured.start();
        captured.succeed();
        captured.holdForReview();
        assertThat(captured.canFailFromReview()).isFalse();
        assertThatThrownBy(captured::failFromReview).isInstanceOf(InvalidStateTransitionException.class);

        PaymentIntent pending = intent();
        pending.start();
        pending.holdForReview();
        pending.failFromReview();
        assertThat(pending.getState()).isEqualTo(PaymentState.FAILED);
    }

    @Test
    void shouldOnlyReverseAHeldPaymentWithMoneyAuthorizedOrCaptured() {
        PaymentIntent pending = intent();
        pending.start();
        pending.holdForReview();
        assertThat(pending.canReverseFromReview()).isFalse();
        assertThatThrownBy(pending::reverseFromReview).isInstanceOf(InvalidStateTransitionException.class);

        PaymentIntent settled = intent();
        settled.start();
        settled.succeed();
        settled.settle();
        settled.holdForReview();
        settled.reverseFromReview();
        assertThat(settled.getState()).isEqualTo(PaymentState.REVERSAL_REQUIRED);
    }

    @Test
    void shouldAllowARefundOnAReconciledPaymentOnlyThroughTheReviewedPath() {
        PaymentIntent intent = intent();
        intent.start();
        intent.succeed();
        intent.settle();
        intent.reconcile();

        assertThatThrownBy(intent::requestRefund).isInstanceOf(InvalidStateTransitionException.class);
        intent.requestReviewedRefund();
        assertThat(intent.getState()).isEqualTo(PaymentState.REFUND_REQUESTED);
    }
}
