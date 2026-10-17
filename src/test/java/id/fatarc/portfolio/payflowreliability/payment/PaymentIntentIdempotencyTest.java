package id.fatarc.portfolio.payflowreliability.payment;

import id.fatarc.portfolio.payflowreliability.common.BusinessException;
import id.fatarc.portfolio.payflowreliability.common.ErrorCode;
import id.fatarc.portfolio.payflowreliability.payment.dto.CreatePaymentIntentRequest;
import id.fatarc.portfolio.payflowreliability.support.IntegrationTest;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PaymentIntentIdempotencyTest extends IntegrationTest {
    private static final CreatePaymentIntentRequest ORDER_1 =
            new CreatePaymentIntentRequest(null, "ORDER-1", new BigDecimal("50000"), "IDR");

    @Test
    void shouldReturnSamePaymentIntentForSameIdempotencyKey() {
        PaymentIntentCreation first = paymentIntentService.create(ORDER_1, "same-key-001", AS_MERCHANT_A);
        PaymentIntentCreation second = paymentIntentService.create(ORDER_1, "same-key-001", AS_MERCHANT_A);

        assertThat(first.replayed()).isFalse();
        assertThat(second.replayed()).isTrue();
        assertThat(second.intent().getId()).isEqualTo(first.intent().getId());
        assertThat(paymentIntentRepository.count()).isEqualTo(1);
        // The replay is answered with the first attempt's risk decision; nothing is decided twice.
        assertThat(auditFor(first.intent())).extracting("eventType")
                .containsExactly("PAYMENT_INTENT_CREATED", "RISK_DECISION", "IDEMPOTENCY_REPLAYED");
    }

    @Test
    void shouldRejectSameIdempotencyKeyWithDifferentPayload() {
        paymentIntentService.create(ORDER_1, "same-key-002", AS_MERCHANT_A);
        CreatePaymentIntentRequest changed = new CreatePaymentIntentRequest(null, "ORDER-2", new BigDecimal("75000"), "IDR");

        assertThatThrownBy(() -> paymentIntentService.create(changed, "same-key-002", AS_MERCHANT_A))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("different request payload")
                .extracting("code").isEqualTo(ErrorCode.IDEMPOTENCY_KEY_REUSED);
        assertThat(paymentIntentRepository.findAll()).singleElement()
                .extracting(PaymentIntent::getMerchantReference).isEqualTo("ORDER-1");
    }

    @Test
    void shouldTreatTheSameAmountAtADifferentScaleAsTheSameRequest() {
        paymentIntentService.create(ORDER_1, "scale-key", AS_MERCHANT_A);
        CreatePaymentIntentRequest sameAmount = new CreatePaymentIntentRequest(null, "ORDER-1", new BigDecimal("50000.00"), "IDR");

        assertThat(paymentIntentService.create(sameAmount, "scale-key", AS_MERCHANT_A).replayed()).isTrue();
    }

    @Test
    void shouldRejectMissingIdempotencyKeyForPaymentCreation() {
        assertThatThrownBy(() -> paymentIntentService.create(ORDER_1, " ", AS_MERCHANT_A))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("Idempotency-Key header is required");
    }

    @Test
    void shouldScopeIdempotencyKeysPerMerchant() {
        PaymentIntent first = paymentIntentService.create(ORDER_1, "shared-key", AS_MERCHANT_A).intent();
        PaymentIntentCreation second = paymentIntentService.create(ORDER_1, "shared-key", AS_MERCHANT_B);

        assertThat(second.replayed()).isFalse();
        assertThat(second.intent().getId()).isNotEqualTo(first.getId());
        assertThat(second.intent().getMerchantId()).isEqualTo(MERCHANT_B);
    }

    @Test
    void shouldCreateExactlyOneIntentWhenTheSameKeyArrivesConcurrently() throws Exception {
        List<Object> results = race(8, () -> paymentIntentService.create(ORDER_1, "race-key", AS_MERCHANT_A).intent().getId());

        assertThat(results).allSatisfy(result -> assertThat(result).isNotInstanceOf(Throwable.class));
        assertThat(results).containsOnly(results.getFirst());
        assertThat(paymentIntentRepository.count()).isEqualTo(1);
    }

    @Test
    void shouldTreatARetriedStartAsANoOp() {
        PaymentIntent intent = paymentIntentService.create(ORDER_1, "start-key", AS_MERCHANT_A).intent();

        paymentIntentService.start(intent.getId(), AS_MERCHANT_A);
        PaymentIntent retried = paymentIntentService.start(intent.getId(), AS_MERCHANT_A);

        assertThat(retried.getState()).isEqualTo(PaymentState.PENDING);
        assertThat(auditFor(intent)).filteredOn(event -> "TRANSACTION_STATE_CHANGED".equals(event.getEventType()))
                .hasSize(1);
    }
}
