package id.fatarc.portfolio.payflowreliability.operations;

import id.fatarc.portfolio.payflowreliability.operations.dto.PaymentCaseFile;
import id.fatarc.portfolio.payflowreliability.payment.PaymentIntent;
import id.fatarc.portfolio.payflowreliability.payment.PaymentState;
import id.fatarc.portfolio.payflowreliability.review.ReviewAction;
import id.fatarc.portfolio.payflowreliability.review.ReviewReason;
import id.fatarc.portfolio.payflowreliability.review.ReviewStatus;
import id.fatarc.portfolio.payflowreliability.review.dto.ReviewCaseView;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class CaseFileAttentionTest {

    private static PaymentIntent settledPayment() {
        PaymentIntent intent = new PaymentIntent("merchant", "ORDER-1", new BigDecimal("100.00"), "IDR", "key");
        intent.start();
        intent.succeed();
        intent.settle();
        return intent;
    }

    private static ReviewCaseView openCase(PaymentIntent intent, ReviewReason reason) {
        return new ReviewCaseView(UUID.randomUUID(), reason, ReviewStatus.OPEN, "PROVIDER_TRANSACTION", "tx-1",
                intent.getId(), intent.getState(), "details", "reconciliation-job", Instant.now(),
                Set.of(ReviewAction.ACKNOWLEDGE), null, null, null, null);
    }

    @Test
    void shouldReportTheNextExpectedStepWhenNothingIsOpen() {
        PaymentCaseFile.Attention attention = OperationsService.attention(settledPayment(), List.of(), List.of());

        assertThat(attention.needsAction()).isFalse();
        assertThat(attention.waitingOn()).isEqualTo("Provider settlement report for reconciliation.");
    }

    @Test
    void shouldMentionOpenReviewReasonsAlongsideTheNextStep() {
        PaymentIntent intent = settledPayment();

        PaymentCaseFile.Attention attention = OperationsService.attention(intent, List.of(),
                List.of(openCase(intent, ReviewReason.AMOUNT_MISMATCH), openCase(intent, ReviewReason.SETTLEMENT_DATE_MISMATCH)));

        assertThat(attention.needsAction()).isTrue();
        assertThat(attention.openReviewCaseIds()).hasSize(2);
        assertThat(attention.waitingOn())
                .startsWith("Provider settlement report for reconciliation.")
                .contains("2 open review reason(s)", "AMOUNT_MISMATCH", "SETTLEMENT_DATE_MISMATCH");
    }

    @Test
    void shouldDescribeAHoldDecision() {
        PaymentIntent intent = settledPayment();
        intent.holdForReview();

        PaymentCaseFile.Attention attention = OperationsService.attention(intent, List.of(),
                List.of(openCase(intent, ReviewReason.AMOUNT_MISMATCH)));

        assertThat(intent.getState()).isEqualTo(PaymentState.MANUAL_REVIEW);
        assertThat(attention.waitingOn()).contains("held from SETTLED", "RELEASE_PAYMENT").doesNotContain("Also:");
    }
}
