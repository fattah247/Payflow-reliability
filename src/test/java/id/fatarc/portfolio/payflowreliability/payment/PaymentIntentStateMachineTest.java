package id.fatarc.portfolio.payflowreliability.payment;

import id.fatarc.portfolio.payflowreliability.common.InvalidStateTransitionException;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Pure domain rules, no Spring context. */
class PaymentIntentStateMachineTest {
    private static final Instant NOW = Instant.parse("2026-10-01T10:00:00Z");

    private static PaymentIntent intent() {
        return new PaymentIntent("merchant", "ORDER-1", new BigDecimal("100.00"), "IDR", "key");
    }

    private static PaymentIntent captured() {
        PaymentIntent intent = intent();
        intent.start();
        intent.succeed(NOW);
        return intent;
    }

    @Test
    void shouldFollowTheCapturePathToReconciliation() {
        PaymentIntent intent = intent();
        intent.start();
        intent.authorize();
        intent.succeed(NOW);
        intent.settle();
        intent.reconcile();
        assertThat(intent.getState()).isEqualTo(PaymentState.RECONCILED);
        assertThat(intent.getCapturedAt()).isEqualTo(NOW);
    }

    @Test
    void shouldRejectRegressionFromSuccessToFailed() {
        PaymentIntent intent = captured();
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
        PaymentIntent captured = captured();
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

        PaymentIntent settled = captured();
        settled.settle();
        settled.holdForReview();
        settled.reverseFromReview();
        assertThat(settled.getState()).isEqualTo(PaymentState.REVERSAL_REQUIRED);
    }

    @Test
    void shouldReturnOnlyTheUnrefundedPartOnReversal() {
        PaymentIntent intent = captured();
        intent.recordRefund(new BigDecimal("30.00"));
        intent.requireReversal();
        intent.markReversed();

        assertThat(intent.getState()).isEqualTo(PaymentState.REVERSED);
        assertThat(intent.getReversedAmount()).isEqualByComparingTo("70.00");
    }

    @Test
    void shouldReverseAnAuthorizationWithoutReturningMoney() {
        PaymentIntent intent = intent();
        intent.start();
        intent.authorize();
        intent.requireReversal();
        intent.markReversed();

        assertThat(intent.getReversedAmount()).isEqualByComparingTo("0");
    }

    @Test
    void shouldBecomeRefundedOnlyWhenTheWholeAmountIsRefunded() {
        PaymentIntent intent = captured();
        assertThat(intent.recordRefund(new BigDecimal("40.00"))).isNull();
        assertThat(intent.getState()).isEqualTo(PaymentState.SUCCESS);
        assertThat(intent.canRequestRefund()).isTrue();

        assertThat(intent.recordRefund(new BigDecimal("60.00"))).isEqualTo(PaymentState.SUCCESS);
        assertThat(intent.getState()).isEqualTo(PaymentState.REFUNDED);
        assertThat(intent.canRequestRefund()).isFalse();
    }

    @Test
    void shouldNeverRecordMoreRefundsThanThePaymentAmount() {
        PaymentIntent intent = captured();
        intent.recordRefund(new BigDecimal("80.00"));
        assertThatThrownBy(() -> intent.recordRefund(new BigDecimal("20.01"))).isInstanceOf(IllegalStateException.class);
        assertThat(intent.getRefundedAmount()).isEqualByComparingTo("80.00");
    }

    @Test
    void shouldOnlyRefundCapturedPaymentsThatAreNotOnHold() {
        PaymentIntent pending = intent();
        pending.start();
        assertThat(pending.canRequestRefund()).isFalse();

        PaymentIntent held = captured();
        held.holdForReview();
        assertThat(held.canRequestRefund()).isFalse();

        PaymentIntent reconciled = captured();
        reconciled.settle();
        reconciled.reconcile();
        assertThat(reconciled.canRequestRefund()).isTrue();
    }

    @Test
    void shouldStoreAmountsAtTheCurrencyPrecision() {
        PaymentIntent yen = new PaymentIntent("merchant", "ORDER-JPY", new BigDecimal("1500"), "JPY", "key");
        PaymentIntent dinar = new PaymentIntent("merchant", "ORDER-KWD", new BigDecimal("12.5"), "KWD", "key");

        assertThat(yen.getAmount().scale()).isZero();
        assertThat(dinar.getAmount()).isEqualTo(new BigDecimal("12.500"));
    }
}
