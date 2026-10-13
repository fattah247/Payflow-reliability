package id.fatarc.portfolio.payflowreliability.payment;

import id.fatarc.portfolio.payflowreliability.support.IntegrationTest;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Webhooks are authenticated by an HMAC over timestamp and raw body; nothing unauthenticated is persisted. */
class WebhookSignatureTest extends IntegrationTest {
    @Autowired
    private MeterRegistry meterRegistry;

    private String successBody(PaymentIntent intent, String eventId) {
        return webhookJson(eventId, "tx-" + eventId, intent.getId(), "SUCCESS", "125000", "IDR");
    }

    @Test
    void shouldApplyACorrectlySignedWebhook() throws Exception {
        PaymentIntent intent = startedIntent("ORDER-SIG-1", "125000");

        postSignedWebhook(successBody(intent, "evt-sig-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.outcome").value("APPLIED"))
                .andExpect(jsonPath("$.paymentIntent.state").value("SUCCESS"));
    }

    @Test
    void shouldRejectMissingForgedAndTamperedSignaturesWithoutWritingAnything() throws Exception {
        PaymentIntent intent = startedIntent("ORDER-SIG-2", "125000");
        String body = successBody(intent, "evt-sig-2");
        long now = clock.instant().getEpochSecond();
        long auditBefore = auditEventRepository.count();
        double rejectedBefore = rejectedCount();

        postWebhook(body, null).andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("INVALID_SIGNATURE"));
        postWebhook(body, "local-demo-signature").andExpect(status().isUnauthorized());
        postWebhook(body, sign("whsec_attacker_guess", now, body)).andExpect(status().isUnauthorized());
        // The signature covers the exact bytes, so a modified body fails even with a fresh, valid-looking header.
        postWebhook(body.replace("125000", "1"), sign(WEBHOOK_SECRET, now, body)).andExpect(status().isUnauthorized());

        assertThat(stateOf(intent)).isEqualTo(PaymentState.PENDING);
        assertThat(providerEventRepository.count()).isZero();
        assertThat(openCases()).isEmpty();
        assertThat(auditEventRepository.count()).isEqualTo(auditBefore);
        assertThat(rejectedCount() - rejectedBefore).isEqualTo(4);
    }

    @Test
    void shouldRejectTimestampsOutsideTheReplayWindow() throws Exception {
        PaymentIntent intent = startedIntent("ORDER-SIG-3", "125000");
        String body = successBody(intent, "evt-sig-3");
        long now = clock.instant().getEpochSecond();

        postWebhook(body, sign(WEBHOOK_SECRET, now - Duration.ofMinutes(6).toSeconds(), body))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("replay")));
        postWebhook(body, sign(WEBHOOK_SECRET, now + Duration.ofMinutes(6).toSeconds(), body))
                .andExpect(status().isUnauthorized());
        postWebhook(body, sign(WEBHOOK_SECRET, now - Duration.ofMinutes(4).toSeconds(), body))
                .andExpect(status().isOk());
    }

    @Test
    void shouldReplayACapturedRequestAsADuplicateWhileItIsStillInsideTheWindow() throws Exception {
        PaymentIntent intent = startedIntent("ORDER-SIG-4", "125000");
        String body = successBody(intent, "evt-sig-4");
        String signature = sign(WEBHOOK_SECRET, clock.instant().getEpochSecond(), body);

        postWebhook(body, signature).andExpect(status().isOk());
        postWebhook(body, signature)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.outcome").value("DUPLICATE_IGNORED"));
        assertThat(ledgerJournalRepository.count()).isEqualTo(1);
    }

    @Test
    void shouldAcceptThePreviousSecretDuringRotation() throws Exception {
        PaymentIntent intent = startedIntent("ORDER-SIG-5", "125000");
        String body = successBody(intent, "evt-sig-5");

        postWebhook(body, sign(PREVIOUS_WEBHOOK_SECRET, clock.instant().getEpochSecond(), body)).andExpect(status().isOk());
    }

    @Test
    void shouldAcceptAHeaderCarryingSignaturesFromOldAndNewSecrets() throws Exception {
        PaymentIntent intent = startedIntent("ORDER-SIG-6", "125000");
        String body = successBody(intent, "evt-sig-6");
        long now = clock.instant().getEpochSecond();
        String retired = sign("whsec_retired", now, body);
        String current = sign(WEBHOOK_SECRET, now, body);

        postWebhook(body, retired + "," + current.substring(current.indexOf("v1="))).andExpect(status().isOk());
    }

    @Test
    void shouldValidateTheBodyOnlyAfterTheSignature() throws Exception {
        String malformed = "{\"providerEventId\": ";
        String incomplete = "{\"providerEventId\":\"evt-x\",\"status\":\"SUCCESS\"}";
        String unknownStatus = webhookJson("evt-y", "tx-y", java.util.UUID.randomUUID(), "SETTLED", "1", "IDR");

        postWebhook(malformed, null).andExpect(status().isUnauthorized());
        postSignedWebhook(malformed).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("MALFORMED_REQUEST"));
        postSignedWebhook(incomplete).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        postSignedWebhook(unknownStatus).andExpect(status().isBadRequest());
    }

    @Test
    void shouldMapOutcomesToStatusCodesTheProviderCanActOn() throws Exception {
        PaymentIntent intent = startedIntent("ORDER-SIG-7", "60000");

        postSignedWebhook(webhookJson("evt-sig-7a", "tx-sig-7", intent.getId(), "SUCCESS", "6000", "IDR"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.outcome").value("SENT_TO_REVIEW"))
                .andExpect(jsonPath("$.reviewReason").value("AMOUNT_MISMATCH"));
        postSignedWebhook(webhookJson("evt-sig-7b", "tx-sig-7", intent.getId(), "SUCCESS", "60000", "IDR"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PAYMENT_ON_HOLD"))
                .andExpect(jsonPath("$.retryable").value(true));
    }

    private double rejectedCount() {
        return meterRegistry.find("payflow.webhook.rejected").counters().stream().mapToDouble(c -> c.count()).sum();
    }
}
