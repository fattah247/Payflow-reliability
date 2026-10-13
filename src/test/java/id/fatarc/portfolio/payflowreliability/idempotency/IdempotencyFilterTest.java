package id.fatarc.portfolio.payflowreliability.idempotency;

import id.fatarc.portfolio.payflowreliability.payment.PaymentIntent;
import id.fatarc.portfolio.payflowreliability.payment.ProviderStatus;
import id.fatarc.portfolio.payflowreliability.support.IntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.ResultActions;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Every money-moving operator call accepts an Idempotency-Key; retries replay the first outcome. */
class IdempotencyFilterTest extends IntegrationTest {
    @Autowired
    private IdempotencyService idempotencyService;

    private ResultActions settleOverHttp(String apiKey, String key, String merchantId) throws Exception {
        var request = as(apiKey, post("/api/settlements/batches?merchantId=" + merchantId));
        if (key != null) {
            request.header("Idempotency-Key", key);
        }
        return mockMvc.perform(request);
    }

    private long batchCount() {
        return jdbcTemplate.queryForObject("select count(*) from settlement_batches", Long.class);
    }

    @Test
    void shouldReplayTheFirstResponseForARetry() throws Exception {
        capturedIntent("ORDER-IDEM-1", "1000");

        String first = settleOverHttp(OPS_ANA_KEY, "settle-1", MERCHANT_A)
                .andExpect(status().isCreated())
                .andExpect(header().doesNotExist("Idempotent-Replayed"))
                .andReturn().getResponse().getContentAsString();
        String retry = settleOverHttp(OPS_ANA_KEY, "settle-1", MERCHANT_A)
                .andExpect(status().isCreated())
                .andExpect(header().string("Idempotent-Replayed", "true"))
                .andReturn().getResponse().getContentAsString();

        assertThat(retry).isEqualTo(first);
        assertThat(batchCount()).isEqualTo(1);
    }

    @Test
    void shouldRejectAKeyReusedForADifferentRequest() throws Exception {
        capturedIntent("ORDER-IDEM-2", "1000");
        settleOverHttp(OPS_ANA_KEY, "settle-2", MERCHANT_A).andExpect(status().isCreated());

        settleOverHttp(OPS_ANA_KEY, "settle-2", MERCHANT_B)
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REUSED"));
    }

    @Test
    void shouldScopeKeysToTheCaller() throws Exception {
        capturedIntent("ORDER-IDEM-3", "1000");
        PaymentIntent other = startedIntent(MERCHANT_B, "ORDER-IDEM-3B", "2000", "IDR");
        webhook(other.getId(), "evt-idem-3b", "tx-idem-3b", ProviderStatus.SUCCESS, "2000");

        settleOverHttp(OPS_ANA_KEY, "shared-key", MERCHANT_A).andExpect(status().isCreated());
        settleOverHttp(OPS_BUDI_KEY, "shared-key", MERCHANT_B)
                .andExpect(status().isCreated())
                .andExpect(header().doesNotExist("Idempotent-Replayed"));
        assertThat(batchCount()).isEqualTo(2);
    }

    @Test
    void shouldReplayADefinitiveRefusalButNotAValidationError() throws Exception {
        settleOverHttp(OPS_ANA_KEY, "settle-4", MERCHANT_A)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("NOTHING_TO_SETTLE"));
        capturedIntent("ORDER-IDEM-4", "1000");
        // Same key, same outcome: a new attempt needs a new key.
        settleOverHttp(OPS_ANA_KEY, "settle-4", MERCHANT_A)
                .andExpect(status().isConflict())
                .andExpect(header().string("Idempotent-Replayed", "true"));

        mockMvc.perform(as(OPS_ANA_KEY, post("/api/settlements/batches?merchantId=" + MERCHANT_A + "&currency=idr")
                        .header("Idempotency-Key", "settle-5")))
                .andExpect(status().isBadRequest());
        // A request that failed validation was never executed, so its key is still free.
        settleOverHttp(OPS_ANA_KEY, "settle-5", MERCHANT_A)
                .andExpect(status().isCreated());
    }

    @Test
    void shouldRejectMalformedKeys() throws Exception {
        settleOverHttp(OPS_ANA_KEY, "has spaces", MERCHANT_A)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    @Test
    void shouldExecuteConcurrentRetriesOnce() throws Exception {
        capturedIntent("ORDER-IDEM-6", "1000");

        List<Object> statuses = race(6, () -> settleOverHttp(OPS_ANA_KEY, "settle-6", MERCHANT_A)
                .andReturn().getResponse().getStatus());

        assertThat(batchCount()).isEqualTo(1);
        assertThat(statuses).containsOnly(201, 409).contains(201);
    }

    @Test
    void shouldReuseAnExpiredKeyOnceUnderConcurrentRetries() throws Exception {
        capturedIntent("ORDER-IDEM-8", "1000");
        settleOverHttp(OPS_ANA_KEY, "settle-8", MERCHANT_A).andExpect(status().isCreated());
        clock.advance(Duration.ofHours(25));
        capturedIntent("ORDER-IDEM-9", "2000");

        List<Object> statuses = race(6, () -> settleOverHttp(OPS_ANA_KEY, "settle-8", MERCHANT_A)
                .andReturn().getResponse().getStatus());

        assertThat(statuses).containsOnly(201, 409).contains(201);
        assertThat(batchCount()).isEqualTo(2);
    }

    @Test
    void shouldForgetRecordsAfterTheRetentionPeriod() throws Exception {
        capturedIntent("ORDER-IDEM-7", "1000");
        settleOverHttp(OPS_ANA_KEY, "settle-7", MERCHANT_A).andExpect(status().isCreated());

        assertThat(idempotencyService.deleteExpired()).isZero();
        clock.advance(Duration.ofHours(25));
        assertThat(idempotencyService.deleteExpired()).isEqualTo(1);
    }
}
