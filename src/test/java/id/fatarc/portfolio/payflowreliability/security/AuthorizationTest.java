package id.fatarc.portfolio.payflowreliability.security;

import id.fatarc.portfolio.payflowreliability.payment.PaymentIntent;
import id.fatarc.portfolio.payflowreliability.support.IntegrationTest;
import org.junit.jupiter.api.Test;

import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Who may call what: merchants see only their own payments; everything operational needs an operator. */
class AuthorizationTest extends IntegrationTest {
    private static final String ORDER = """
            {"merchantReference": "ORDER-AUTH-1", "amount": 125000, "currency": "IDR"}""";

    @Test
    void shouldRequireAnApiKey() throws Exception {
        mockMvc.perform(get("/api/review-cases"))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string("WWW-Authenticate", "Bearer"))
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
        mockMvc.perform(as("pf_m_not-a-real-key", get("/api/review-cases")))
                .andExpect(status().isUnauthorized());
        postIntent(null, "auth-key-0", ORDER).andExpect(status().isUnauthorized());
    }

    @Test
    void shouldTakeTheMerchantFromTheApiKey() throws Exception {
        postIntent(merchantAKey, "auth-key-1", ORDER)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.merchantId").value(MERCHANT_A));
        postIntent(merchantAKey, "auth-key-2", ORDER.replace("{", "{\"merchantId\": \"merchant-b\", "))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
    }

    @Test
    void shouldHideOtherMerchantsPaymentsAndRefunds() throws Exception {
        PaymentIntent intent = paidIntent("ORDER-AUTH-2", "125000");

        mockMvc.perform(as(merchantBKey, get("/api/payment-intents/" + intent.getId())))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("PAYMENT_INTENT_NOT_FOUND"));
        mockMvc.perform(json(as(merchantBKey, post("/api/refunds/payment-intents/" + intent.getId())), """
                        {"amount": 1000, "reason": "not mine"}""").header("Idempotency-Key", "refund-b-1"))
                .andExpect(status().isNotFound());
        mockMvc.perform(as(merchantBKey, post("/api/payment-intents/" + intent.getId() + "/start")))
                .andExpect(status().isNotFound());

        mockMvc.perform(as(merchantAKey, get("/api/payment-intents/" + intent.getId()))).andExpect(status().isOk());
        mockMvc.perform(as(OPS_ANA_KEY, get("/api/payment-intents/" + intent.getId()))).andExpect(status().isOk());
    }

    @Test
    void shouldKeepOperationalEndpointsForOperators() throws Exception {
        for (String path : new String[]{"/api/review-cases", "/api/operations/summary", "/api/ledger/balances",
                "/api/outbox", "/api/settlements/batches", "/api/audit-events?aggregateType=MERCHANT&aggregateId=merchant-a",
                "/actuator/metrics"}) {
            mockMvc.perform(as(merchantAKey, get(path))).andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value("FORBIDDEN"));
            mockMvc.perform(as(OPS_ANA_KEY, get(path))).andExpect(status().isOk());
        }
        mockMvc.perform(as(merchantAKey, post("/api/settlements/batches").param("merchantId", MERCHANT_A)))
                .andExpect(status().isForbidden());
    }

    @Test
    void shouldOnlyLetMerchantsCreatePayments() throws Exception {
        postIntent(OPS_ANA_KEY, "auth-key-3", ORDER).andExpect(status().isForbidden());
    }

    @Test
    void shouldLeaveHealthPublicAndTheProviderSimulatorBehindItsOwnKey() throws Exception {
        mockMvc.perform(get("/actuator/health")).andExpect(status().isOk());
        mockMvc.perform(get("/actuator/health/readiness")).andExpect(status().isOk());
        mockMvc.perform(get("/provider-sim/v1/refunds/some-key")).andExpect(status().isUnauthorized());
        mockMvc.perform(as(merchantAKey, get("/provider-sim/v1/refunds/some-key"))).andExpect(status().isUnauthorized());
    }

    @Test
    void shouldStopAcceptingARevokedKey() throws Exception {
        var view = merchantService.get(MERCHANT_A);
        merchantService.revokeKey(MERCHANT_A, view.apiKeys().getFirst().keyId(), OPS_ANA);

        postIntent(merchantAKey, "auth-key-4", ORDER).andExpect(status().isUnauthorized());
    }

    @Test
    void shouldNeverStoreOrReturnTheRawKey() throws Exception {
        var keyHashes = jdbcTemplate.queryForList("select key_hash from merchant_api_keys", String.class);

        org.assertj.core.api.Assertions.assertThat(keyHashes).doesNotContain(merchantAKey)
                .contains(id.fatarc.portfolio.payflowreliability.common.Hashing.sha256Hex(merchantAKey));
        mockMvc.perform(as(OPS_ANA_KEY, get("/api/merchants/" + MERCHANT_A)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.apiKeys[0].keyPrefix").value(startsWith("pf_m_")))
                .andExpect(content -> org.assertj.core.api.Assertions.assertThat(
                        content.getResponse().getContentAsString()).doesNotContain(merchantAKey));
    }
}
