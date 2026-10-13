package id.fatarc.portfolio.payflowreliability.merchant;

import com.fasterxml.jackson.databind.JsonNode;
import id.fatarc.portfolio.payflowreliability.support.IntegrationTest;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class MerchantOnboardingTest extends IntegrationTest {
    private static final String ORDER = """
            {"merchantReference": "ORDER-ONB-1", "amount": 1500, "currency": "JPY"}""";

    @Test
    void shouldRegisterAMerchantAndShowItsKeyExactlyOnce() throws Exception {
        JsonNode registration = body(mockMvc.perform(json(as(OPS_ANA_KEY, post("/api/merchants")), """
                        {"merchantId": "merchant-c", "displayName": "Merchant C"}"""))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.merchant.status").value("ACTIVE"))
                .andExpect(jsonPath("$.merchant.createdBy").value("operator:ops-ana"))
                .andExpect(jsonPath("$.apiKey.apiKey").value(startsWith("pf_m_"))));
        String key = registration.at("/apiKey/apiKey").asText();

        postIntent(key, "onb-key-1", ORDER)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.merchantId").value("merchant-c"))
                .andExpect(jsonPath("$.amount").value(1500));
    }

    @Test
    void shouldRejectDuplicateAndInvalidMerchants() throws Exception {
        mockMvc.perform(json(as(OPS_ANA_KEY, post("/api/merchants")), """
                        {"merchantId": "merchant-a", "displayName": "Again"}"""))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("MERCHANT_ALREADY_EXISTS"));
        mockMvc.perform(json(as(OPS_ANA_KEY, post("/api/merchants")), """
                        {"merchantId": "a b", "displayName": ""}"""))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        mockMvc.perform(json(as(merchantAKey, post("/api/merchants")), """
                        {"merchantId": "merchant-z", "displayName": "Self-service"}"""))
                .andExpect(status().isForbidden());
    }

    @Test
    void shouldRotateKeysWithoutDowntime() throws Exception {
        JsonNode issued = body(mockMvc.perform(as(OPS_ANA_KEY, post("/api/merchants/" + MERCHANT_A + "/api-keys")))
                .andExpect(status().isCreated()));
        String newKey = issued.get("apiKey").asText();
        String oldKeyId = merchantService.get(MERCHANT_A).apiKeys().getFirst().keyId().toString();

        postIntent(merchantAKey, "rot-1", ORDER).andExpect(status().isCreated());
        postIntent(newKey, "rot-2", ORDER.replace("ONB-1", "ONB-2")).andExpect(status().isCreated());

        mockMvc.perform(as(OPS_ANA_KEY, delete("/api/merchants/" + MERCHANT_A + "/api-keys/" + oldKeyId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.apiKeys[0].revokedBy").value("operator:ops-ana"));

        postIntent(merchantAKey, "rot-3", ORDER).andExpect(status().isUnauthorized());
        postIntent(newKey, "rot-4", ORDER.replace("ONB-1", "ONB-4")).andExpect(status().isCreated());
        assertThat(auditEventRepository.findByAggregateTypeAndAggregateIdOrderByCreatedAtAsc("MERCHANT", MERCHANT_A))
                .extracting("eventType").containsExactly("MERCHANT_REGISTERED", "API_KEY_ISSUED", "API_KEY_ISSUED", "API_KEY_REVOKED");
    }
}
