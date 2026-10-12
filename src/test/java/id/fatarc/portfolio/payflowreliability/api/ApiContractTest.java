package id.fatarc.portfolio.payflowreliability.api;

import id.fatarc.portfolio.payflowreliability.payment.PaymentIntent;
import id.fatarc.portfolio.payflowreliability.payment.ProviderStatus;
import id.fatarc.portfolio.payflowreliability.support.IntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** HTTP-level contract: status codes, headers, and error bodies that clients and the provider rely on. */
@AutoConfigureMockMvc
class ApiContractTest extends IntegrationTest {
    private static final String CREATE_BODY = """
            {"merchantId": "merchant-demo-01", "merchantReference": "ORDER-9001", "amount": 125000, "currency": "IDR"}
            """;

    @Autowired
    private MockMvc mockMvc;

    private ResultActions postIntent(String key, String body) throws Exception {
        return mockMvc.perform(post("/api/payment-intents")
                .header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body));
    }

    private ResultActions sendWebhook(String signature, UUID paymentIntentId, String eventId, String status, String amount) throws Exception {
        var request = post("/fake-provider/webhook")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"providerEventId": "%s", "providerTransactionId": "tx-%s", "paymentIntentId": "%s",
                         "status": "%s", "amount": %s, "currency": "IDR"}
                        """.formatted(eventId, paymentIntentId, paymentIntentId, status, amount));
        if (signature != null) {
            request.header("X-Provider-Signature", signature);
        }
        return mockMvc.perform(request);
    }

    @Test
    void shouldReturn201ForANewIntentAnd200WithReplayHeaderForARetry() throws Exception {
        postIntent("api-key-1", CREATE_BODY)
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", startsWith("/api/payment-intents/")))
                .andExpect(header().doesNotExist("Idempotent-Replayed"))
                .andExpect(jsonPath("$.state").value("CREATED"));

        postIntent("api-key-1", CREATE_BODY)
                .andExpect(status().isOk())
                .andExpect(header().string("Idempotent-Replayed", "true"));
    }

    @Test
    void shouldReturn422WithAStableCodeWhenAKeyIsReusedForADifferentPayload() throws Exception {
        postIntent("api-key-2", CREATE_BODY).andExpect(status().isCreated());

        postIntent("api-key-2", CREATE_BODY.replace("125000", "1"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REUSED"))
                .andExpect(jsonPath("$.retryable").value(false))
                .andExpect(jsonPath("$.requestId").isNotEmpty());
    }

    @Test
    void shouldEchoACallerRequestIdAndStampItOnAuditEvents() throws Exception {
        String body = mockMvc.perform(post("/api/payment-intents")
                        .header("Idempotency-Key", "api-key-3")
                        .header("X-Request-Id", "trace-abc-123")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(CREATE_BODY))
                .andExpect(header().string("X-Request-Id", "trace-abc-123"))
                .andReturn().getResponse().getContentAsString();
        String id = body.replaceAll(".*\"id\":\"([^\"]+)\".*", "$1");

        assertThat(auditEventRepository.findByPaymentIntentIdOrderByCreatedAtAsc(UUID.fromString(id)))
                .extracting("requestId").containsOnly("trace-abc-123");
    }

    @Test
    void shouldReplaceAnUnsafeRequestIdWithAGeneratedOne() throws Exception {
        mockMvc.perform(get("/api/review-cases").header("X-Request-Id", "bad id\nwith newline"))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Request-Id", matchesPattern("[0-9a-f-]{36}")));
    }

    @Test
    void shouldReturn400WithFieldDetailsForAnInvalidBody() throws Exception {
        postIntent("api-key-4", """
                {"merchantId": "", "merchantReference": "ORDER-1", "amount": 0, "currency": "idr"}
                """)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.message", allOf(containsString("merchantId"), containsString("amount"), containsString("currency"))));
    }

    @Test
    void shouldRejectSubCentAmountsInsteadOfSilentlyRoundingThem() throws Exception {
        postIntent("api-key-5", CREATE_BODY.replace("125000", "100.005"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message", containsString("2 decimal places")));
        assertThat(paymentIntentRepository.count()).isZero();
    }

    @Test
    void shouldReturnAmountsAtTheStoredScaleFromEveryEndpoint() throws Exception {
        String created = postIntent("api-key-6", CREATE_BODY)
                .andReturn().getResponse().getContentAsString();
        String id = created.replaceAll(".*\"id\":\"([^\"]+)\".*", "$1");
        String fetched = mockMvc.perform(get("/api/payment-intents/" + id)).andReturn().getResponse().getContentAsString();

        assertThat(created).contains("\"amount\":125000.00");
        assertThat(fetched).contains("\"amount\":125000.00");
    }

    @Test
    void shouldReturn404ForAnUnknownPayment() throws Exception {
        mockMvc.perform(get("/api/payment-intents/" + UUID.randomUUID()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("PAYMENT_INTENT_NOT_FOUND"));
    }

    @Test
    void shouldReturn409ForAnInvalidTransition() throws Exception {
        PaymentIntent intent = capturedIntent("ORDER-9002", "1000");

        mockMvc.perform(post("/api/payment-intents/" + intent.getId() + "/start"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INVALID_STATE_TRANSITION"));
    }

    @Test
    void shouldReturn401ForAMissingOrForgedSignature() throws Exception {
        PaymentIntent intent = startedIntent("ORDER-9003", "1000");

        sendWebhook(null, intent.getId(), "evt-9003-a", "SUCCESS", "1000")
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("INVALID_SIGNATURE"));
        sendWebhook("forged", intent.getId(), "evt-9003-b", "SUCCESS", "1000")
                .andExpect(status().isUnauthorized());
        assertThat(providerEventRepository.count()).isZero();
    }

    @Test
    void shouldMapWebhookOutcomesToStatusCodesTheProviderCanActOn() throws Exception {
        PaymentIntent intent = startedIntent("ORDER-9004", "1000");

        sendWebhook(SIGNATURE, intent.getId(), "evt-9004-wrong", "SUCCESS", "999")
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.outcome").value("SENT_TO_REVIEW"))
                .andExpect(jsonPath("$.reviewReason").value("AMOUNT_MISMATCH"))
                .andExpect(jsonPath("$.reviewCaseId").isNotEmpty())
                .andExpect(jsonPath("$.paymentIntent.state").value("MANUAL_REVIEW"))
                .andExpect(jsonPath("$.paymentIntent.stateBeforeReview").value("PENDING"));

        sendWebhook(SIGNATURE, intent.getId(), "evt-9004-right", "SUCCESS", "1000")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PAYMENT_ON_HOLD"))
                .andExpect(jsonPath("$.retryable").value(true));

        sendWebhook(SIGNATURE, intent.getId(), "evt-9004-wrong", "SUCCESS", "999")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.outcome").value("DUPLICATE_IGNORED"))
                .andExpect(jsonPath("$.originalOutcome").value("SENT_TO_REVIEW"));
    }

    @Test
    void shouldResolveAReviewCaseOverHttpAndListItsAllowedActions() throws Exception {
        PaymentIntent intent = startedIntent("ORDER-9005", "1000");
        webhook(intent.getId(), "evt-9005", "tx-9005", ProviderStatus.SUCCESS, "1");
        UUID caseId = openCases().getFirst().getId();

        mockMvc.perform(get("/api/review-cases").param("paymentIntentId", intent.getId().toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].allowedActions", containsInAnyOrder("RELEASE_PAYMENT", "FAIL_PAYMENT")));

        mockMvc.perform(post("/api/review-cases/" + caseId + "/resolve")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"actor": "ops-ana", "note": "Customer confirmed the order", "action": "ACKNOWLEDGE"}
                                """))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("RESOLUTION_ACTION_NOT_APPLICABLE"));

        mockMvc.perform(post("/api/review-cases/" + caseId + "/resolve")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"actor": "ops-ana", "note": "Provider sent a bad amount; will resend", "action": "RELEASE_PAYMENT"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("RESOLVED"))
                .andExpect(jsonPath("$.paymentState").value("PENDING"));

        mockMvc.perform(get("/api/review-cases").param("status", "ALL"))
                .andExpect(jsonPath("$", hasSize(1)));
        mockMvc.perform(get("/api/review-cases").param("status", "bogus"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void shouldReturnStatusCodesForTheRefundLifecycle() throws Exception {
        PaymentIntent captured = capturedIntent("ORDER-9006", "1000");
        String refundBody = """
                {"amount": 1000, "reason": "customer request"}
                """;

        mockMvc.perform(post("/api/refunds/payment-intents/" + captured.getId())
                        .contentType(MediaType.APPLICATION_JSON).content(refundBody))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("REQUESTED"));
        mockMvc.perform(post("/api/refunds/payment-intents/" + captured.getId())
                        .contentType(MediaType.APPLICATION_JSON).content(refundBody))
                .andExpect(status().isOk())
                .andExpect(header().string("Idempotent-Replayed", "true"));

        PaymentIntent reconciled = reconciledIntent("ORDER-9007", "1000");
        mockMvc.perform(post("/api/refunds/payment-intents/" + reconciled.getId())
                        .contentType(MediaType.APPLICATION_JSON).content(refundBody))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.status").value("PENDING_REVIEW"));
    }

    @Test
    void shouldAssembleACaseFileThatExplainsWhatAPaymentIsWaitingOn() throws Exception {
        PaymentIntent intent = startedIntent("ORDER-9008", "5000");
        webhook(intent.getId(), "evt-9008-auth", "tx-9008", ProviderStatus.AUTHORIZED, "5000");
        webhook(intent.getId(), "evt-9008-wrong", "tx-9008", ProviderStatus.SUCCESS, "4000");

        mockMvc.perform(get("/api/payment-intents/" + intent.getId() + "/case-file"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.payment.state").value("MANUAL_REVIEW"))
                .andExpect(jsonPath("$.attention.needsAction").value(true))
                .andExpect(jsonPath("$.attention.waitingOn", containsString("held from AUTHORIZED")))
                .andExpect(jsonPath("$.attention.openReviewCaseIds", hasSize(1)))
                .andExpect(jsonPath("$.providerTransactions[0].providerStatus").value("AUTHORIZED"))
                .andExpect(jsonPath("$.providerEvents[*].outcome", contains("APPLIED", "SENT_TO_REVIEW")))
                .andExpect(jsonPath("$.reviewCases[0].allowedActions", hasItem("REQUIRE_REVERSAL")))
                .andExpect(jsonPath("$.timeline[*].eventType", hasItems("PAYMENT_INTENT_CREATED", "WEBHOOK_SENT_TO_REVIEW")));
    }

    @Test
    void shouldSummarizeWhatNeedsAttention() throws Exception {
        capturedIntent("ORDER-9009", "1000");
        capturedIntent("ORDER-9010", "2500");
        PaymentIntent held = startedIntent("ORDER-9011", "1000");
        webhook(held.getId(), "evt-9011", "tx-9011", ProviderStatus.SUCCESS, "1");

        mockMvc.perform(get("/api/operations/summary"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.paymentsByState.SUCCESS").value(2))
                .andExpect(jsonPath("$.paymentsByState.REFUNDED").value(0))
                .andExpect(jsonPath("$.reviewQueue.openCases").value(1))
                .andExpect(jsonPath("$.reviewQueue.openByReason.AMOUNT_MISMATCH").value(1))
                .andExpect(jsonPath("$.reviewQueue.paymentsOnHold").value(1))
                .andExpect(jsonPath("$.reviewQueue.oldestOpenedAt").isNotEmpty())
                .andExpect(jsonPath("$.awaitingSettlement[0].merchantId").value(MERCHANT))
                .andExpect(jsonPath("$.awaitingSettlement[0].transactionCount").value(2))
                .andExpect(jsonPath("$.awaitingSettlement[0].totalAmount").value(3500.0));
    }

    @Test
    void shouldReturnConsistentErrorsForMissingParametersAndUnknownRoutes() throws Exception {
        mockMvc.perform(post("/api/settlements/batches"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        mockMvc.perform(get("/api/does-not-exist"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("ROUTE_NOT_FOUND"));
        mockMvc.perform(get("/api/audit-events"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        mockMvc.perform(get("/api/operations/summary").accept(MediaType.APPLICATION_XML))
                .andExpect(status().isNotAcceptable());
    }

    @Test
    void shouldCreateSettlementBatchesWith201() throws Exception {
        capturedIntent("ORDER-9012", "1000");

        mockMvc.perform(post("/api/settlements/batches").param("merchantId", MERCHANT))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.transactionCount").value(1));
        mockMvc.perform(post("/api/settlements/batches").param("merchantId", MERCHANT))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("NOTHING_TO_SETTLE"));
    }
}
