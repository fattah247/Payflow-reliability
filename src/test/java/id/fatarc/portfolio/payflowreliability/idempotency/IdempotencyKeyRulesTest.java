package id.fatarc.portfolio.payflowreliability.idempotency;

import id.fatarc.portfolio.payflowreliability.common.BusinessException;
import id.fatarc.portfolio.payflowreliability.common.ErrorCode;
import id.fatarc.portfolio.payflowreliability.payment.PaymentIntent;
import id.fatarc.portfolio.payflowreliability.payment.PaymentIntentCreation;
import id.fatarc.portfolio.payflowreliability.payment.dto.CreatePaymentIntentRequest;
import id.fatarc.portfolio.payflowreliability.refund.RefundRequest;
import id.fatarc.portfolio.payflowreliability.refund.RefundSubmission;
import id.fatarc.portfolio.payflowreliability.refund.dto.CreateRefundRequest;
import id.fatarc.portfolio.payflowreliability.support.IntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.ResultActions;

import java.math.BigDecimal;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The rules of ADR 024, one test each. Together with PaymentIntentIdempotencyTest, IdempotencyFilterTest,
 * RefundTest and OutboundIdempotencyKeyTest they cover every path a key travels: client requests, operator
 * calls, and calls this service makes to the provider.
 */
class IdempotencyKeyRulesTest extends IntegrationTest {
    private static final String REPORT = "{\"rows\":[{\"providerTransactionId\":\"tx-unknown\",\"amount\":1000,"
            + "\"currency\":\"IDR\",\"providerStatus\":\"SUCCESS\"}]}";

    private ResultActions operatorPost(String path, String key, String body) throws Exception {
        var request = as(OPS_ANA_KEY, post(path)).header("Idempotency-Key", key);
        return mockMvc.perform(body == null ? request : json(request, body));
    }

    private long reviewCaseCount() {
        return jdbcTemplate.queryForObject("select count(*) from review_cases", Long.class);
    }

    @Test
    void shouldRecognizeAnOperatorRetryWhoseJsonWasSerializedDifferently() throws Exception {
        operatorPost("/api/reconciliation/reports", "report-1", REPORT).andExpect(status().isOk());
        String reserialized = """
                {"rows": [
                    {"providerStatus": "SUCCESS", "currency": "IDR", "amount": 1000.00, "providerTransactionId": "tx-unknown"}
                ]}""";

        operatorPost("/api/reconciliation/reports", "report-1", reserialized)
                .andExpect(status().isOk())
                .andExpect(header().string("Idempotent-Replayed", "true"));
        assertThat(reviewCaseCount()).as("executed once").isEqualTo(1);
    }

    @Test
    void shouldRefuseAnOperatorKeyReusedWithADifferentAmount() throws Exception {
        operatorPost("/api/reconciliation/reports", "report-2", REPORT).andExpect(status().isOk());

        operatorPost("/api/reconciliation/reports", "report-2", REPORT.replace("1000", "1001"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REUSED"));
    }

    @Test
    void shouldTreatQueryParametersInAnyOrderAsTheSameRequest() throws Exception {
        capturedIntent("ORDER-RULE-3", "1000");
        operatorPost("/api/settlements/batches?merchantId=" + MERCHANT_A + "&currency=IDR", "settle-rule-3", null)
                .andExpect(status().isCreated());

        operatorPost("/api/settlements/batches?currency=IDR&merchantId=" + MERCHANT_A, "settle-rule-3", null)
                .andExpect(status().isCreated())
                .andExpect(header().string("Idempotent-Replayed", "true"));
    }

    @Test
    void shouldRefuseAKeyReusedForAnotherOperation() throws Exception {
        capturedIntent("ORDER-RULE-4", "1000");
        operatorPost("/api/settlements/batches?merchantId=" + MERCHANT_A, "one-key", null).andExpect(status().isCreated());

        // A key names one business operation. Answering a different operation with the stored response of
        // the first, or executing it as new, would both be wrong: the client has a bug and must be told.
        operatorPost("/api/outbox/run", "one-key", null)
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REUSED"));
    }

    @Test
    void shouldKeepPaymentKeysForeverWhileOperatorKeysExpire() {
        CreatePaymentIntentRequest order = new CreatePaymentIntentRequest(null, "ORDER-RULE-5", new BigDecimal("50000"), "IDR");
        PaymentIntent created = paymentIntentService.create(order, "checkout-rule-5", AS_MERCHANT_A).intent();

        // Far beyond the 24-hour retention of stored operator responses: a payment key lives as long as the
        // payment, because a late retry that created a second payment would charge the customer twice.
        clock.advance(Duration.ofDays(30));
        PaymentIntentCreation retried = paymentIntentService.create(order, "checkout-rule-5", AS_MERCHANT_A);

        assertThat(retried.replayed()).isTrue();
        assertThat(retried.intent().getId()).isEqualTo(created.getId());
    }

    @Test
    void shouldStoreTheCanonicalFingerprintOfTheCreatingRequest() {
        PaymentIntent created = createIntent("ORDER-RULE-6", "50000");

        assertThat(created.getRequestFingerprint())
                .isEqualTo(PaymentIntent.fingerprintOf("ORDER-RULE-6", new BigDecimal("50000.00"), "IDR"))
                .isNotEqualTo(PaymentIntent.fingerprintOf("ORDER-RULE-6", new BigDecimal("50001"), "IDR"));
        assertThat(jdbcTemplate.queryForObject("select request_fingerprint from payment_intents where id = ?",
                String.class, created.getId())).isEqualTo(created.getRequestFingerprint());
    }

    @Test
    void shouldScopeRefundKeysToTheirPayment() {
        PaymentIntent first = paidIntent("ORDER-RULE-7A", "50000");
        PaymentIntent second = paidIntent("ORDER-RULE-7B", "50000");

        RefundRequest firstRefund = requestRefund(first, "10000", "refund-1");
        RefundRequest secondRefund = requestRefund(second, "10000", "refund-1");
        RefundSubmission retry = refundService.requestRefund(first.getId(),
                new CreateRefundRequest(new BigDecimal("10000.00"), "Customer request"), "refund-1", AS_MERCHANT_A);

        assertThat(secondRefund.getId()).as("same key, another payment: another refund").isNotEqualTo(firstRefund.getId());
        assertThat(retry.replayed()).isTrue();
        assertThat(retry.refund().getId()).isEqualTo(firstRefund.getId());
        assertThatThrownBy(() -> refundService.requestRefund(first.getId(),
                new CreateRefundRequest(new BigDecimal("10000"), "Damaged item"), "refund-1", AS_MERCHANT_A))
                .isInstanceOf(BusinessException.class)
                .extracting("code").isEqualTo(ErrorCode.IDEMPOTENCY_KEY_REUSED);
    }
}
