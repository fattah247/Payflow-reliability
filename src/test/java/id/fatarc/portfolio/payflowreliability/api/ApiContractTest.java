package id.fatarc.portfolio.payflowreliability.api;

import com.fasterxml.jackson.databind.JsonNode;
import id.fatarc.portfolio.payflowreliability.payment.PaymentIntent;
import id.fatarc.portfolio.payflowreliability.payment.ProviderStatus;
import id.fatarc.portfolio.payflowreliability.support.IntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** HTTP-level contract: status codes, headers, and error bodies that clients and operators rely on. */
class ApiContractTest extends IntegrationTest {
    private static final String CREATE_BODY = """
            {"merchantReference": "ORDER-9001", "amount": 125000, "currency": "IDR"}""";

    @Test
    void shouldReturn201ForANewIntentAnd200WithReplayHeaderForARetry() throws Exception {
        postIntent(merchantAKey, "api-key-1", CREATE_BODY)
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", startsWith("/api/payment-intents/")))
                .andExpect(header().doesNotExist("Idempotent-Replayed"))
                .andExpect(jsonPath("$.state").value("CREATED"));
        postIntent(merchantAKey, "api-key-1", CREATE_BODY)
                .andExpect(status().isOk())
                .andExpect(header().string("Idempotent-Replayed", "true"));
    }

    @Test
    void shouldReturn422WithAStableCodeWhenAKeyIsReusedForADifferentPayload() throws Exception {
        postIntent(merchantAKey, "api-key-2", CREATE_BODY).andExpect(status().isCreated());

        postIntent(merchantAKey, "api-key-2", CREATE_BODY.replace("125000", "1"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REUSED"))
                .andExpect(jsonPath("$.retryable").value(false));
    }

    @Test
    void shouldRequireAnIdempotencyKeyForPaymentAndRefundCreation() throws Exception {
        postIntent(merchantAKey, null, CREATE_BODY)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message", containsString("Idempotency-Key")));
        PaymentIntent intent = paidIntent("ORDER-9002", "1000");
        mockMvc.perform(json(as(merchantAKey, post("/api/refunds/payment-intents/" + intent.getId())), """
                        {"amount": 1000, "reason": "customer request"}"""))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message", containsString("Idempotency-Key")));
    }

    @Test
    void shouldEchoACallerRequestIdAndStampItOnAuditEvents() throws Exception {
        JsonNode body = body(mockMvc.perform(json(as(merchantAKey, post("/api/payment-intents")), CREATE_BODY)
                        .header("Idempotency-Key", "api-key-3")
                        .header("X-Request-Id", "trace-abc-123"))
                .andExpect(header().string("X-Request-Id", "trace-abc-123")));

        assertThat(auditEventRepository.findByPaymentIntentIdOrderBySequenceNumberAsc(UUID.fromString(body.get("id").asText())))
                .extracting("requestId").containsOnly("trace-abc-123");
    }

    @Test
    void shouldReplaceAnUnsafeRequestIdWithAGeneratedOne() throws Exception {
        mockMvc.perform(as(OPS_ANA_KEY, get("/api/review-cases")).header("X-Request-Id", "bad id\nwith newline"))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Request-Id", matchesPattern("[0-9a-f-]{36}")));
    }

    @Test
    void shouldReturn400WithFieldDetailsForAnInvalidBody() throws Exception {
        postIntent(merchantAKey, "api-key-4", """
                {"merchantReference": "", "amount": 0, "currency": "idr"}""")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.message", allOf(containsString("merchantReference"), containsString("amount"),
                        containsString("currency"))));
    }

    @Test
    void shouldRejectAmountsMorePreciseThanTheCurrencyInsteadOfRoundingThem() throws Exception {
        postIntent(merchantAKey, "api-key-5", CREATE_BODY.replace("125000", "100.005"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message", containsString("2 decimal places")));
        postIntent(merchantAKey, "api-key-6", CREATE_BODY.replace("125000", "10.5").replace("IDR", "JPY"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message", containsString("no minor unit")));
        postIntent(merchantAKey, "api-key-7", CREATE_BODY.replace("IDR", "ABC"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message", containsString("currency")));
        assertThat(paymentIntentRepository.count()).isZero();
    }

    @Test
    void shouldReturnAmountsAtTheCurrencyScaleFromEveryEndpoint() throws Exception {
        String idr = postIntent(merchantAKey, "api-key-8", CREATE_BODY).andReturn().getResponse().getContentAsString();
        String jpy = postIntent(merchantAKey, "api-key-9", CREATE_BODY.replace("125000", "1500").replace("IDR", "JPY"))
                .andReturn().getResponse().getContentAsString();
        String kwd = postIntent(merchantAKey, "api-key-10", CREATE_BODY.replace("125000", "1.5").replace("IDR", "KWD"))
                .andReturn().getResponse().getContentAsString();
        String id = objectMapper.readTree(idr).get("id").asText();
        String fetched = mockMvc.perform(as(merchantAKey, get("/api/payment-intents/" + id)))
                .andReturn().getResponse().getContentAsString();

        assertThat(idr).contains("\"amount\":125000.00");
        assertThat(fetched).contains("\"amount\":125000.00");
        assertThat(jpy).contains("\"amount\":1500,");
        assertThat(kwd).contains("\"amount\":1.500");
    }

    @Test
    void shouldReturn404ForAnUnknownPayment() throws Exception {
        mockMvc.perform(as(OPS_ANA_KEY, get("/api/payment-intents/" + UUID.randomUUID())))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("PAYMENT_INTENT_NOT_FOUND"));
    }

    @Test
    void shouldReturn409ForAnInvalidTransition() throws Exception {
        PaymentIntent intent = capturedIntent("ORDER-9003", "1000");

        mockMvc.perform(as(merchantAKey, post("/api/payment-intents/" + intent.getId() + "/start")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INVALID_STATE_TRANSITION"));
    }

    @Test
    void shouldResolveAReviewCaseOverHttpAndListItsAllowedActions() throws Exception {
        PaymentIntent intent = startedIntent("ORDER-9005", "1000");
        webhook(intent.getId(), "evt-9005", "tx-9005", ProviderStatus.SUCCESS, "1");
        UUID caseId = openCases().getFirst().getId();

        mockMvc.perform(as(OPS_ANA_KEY, get("/api/review-cases").param("paymentIntentId", intent.getId().toString())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].allowedActions", containsInAnyOrder("RELEASE_PAYMENT", "FAIL_PAYMENT")));
        mockMvc.perform(json(as(OPS_ANA_KEY, post("/api/review-cases/" + caseId + "/resolve")), """
                        {"note": "Customer confirmed the order", "action": "ACKNOWLEDGE"}"""))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("RESOLUTION_ACTION_NOT_APPLICABLE"));
        mockMvc.perform(json(as(OPS_ANA_KEY, post("/api/review-cases/" + caseId + "/resolve")), """
                        {"note": "Provider sent a bad amount; will resend", "action": "RELEASE_PAYMENT"}"""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("RESOLVED"))
                .andExpect(jsonPath("$.resolvedBy").value("operator:ops-ana"))
                .andExpect(jsonPath("$.paymentState").value("PENDING"));

        mockMvc.perform(as(OPS_ANA_KEY, get("/api/review-cases").param("status", "ALL"))).andExpect(jsonPath("$", hasSize(1)));
        mockMvc.perform(as(OPS_ANA_KEY, get("/api/review-cases").param("status", "bogus"))).andExpect(status().isBadRequest());
    }

    @Test
    void shouldReturn202ForAProposalAnd403WhenTheProposerApprovesIt() throws Exception {
        PaymentIntent intent = settledIntent("ORDER-9006", "1000");
        providerSimulator.chargeback(intent.getId(), null);
        deliverWebhooks();
        UUID caseId = openCases().getFirst().getId();

        mockMvc.perform(json(as(OPS_ANA_KEY, post("/api/review-cases/" + caseId + "/resolve")), """
                        {"note": "Chargeback is valid", "action": "REQUIRE_REVERSAL"}"""))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.status").value("AWAITING_APPROVAL"));
        mockMvc.perform(json(as(OPS_ANA_KEY, post("/api/review-cases/" + caseId + "/approve")), """
                        {"note": "approving my own"}"""))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FOUR_EYES_REQUIRED"));
        mockMvc.perform(json(as(OPS_BUDI_KEY, post("/api/review-cases/" + caseId + "/approve")), """
                        {"note": "Evidence checked"}"""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.approvedBy").value("operator:ops-budi"))
                .andExpect(jsonPath("$.paymentState").value("REVERSAL_REQUIRED"));
    }

    @Test
    void shouldReturnStatusCodesForTheRefundLifecycle() throws Exception {
        PaymentIntent captured = paidIntent("ORDER-9007", "1000");
        String refundBody = """
                {"amount": 400, "reason": "customer request"}""";

        String created = mockMvc.perform(json(as(merchantAKey, post("/api/refunds/payment-intents/" + captured.getId())), refundBody)
                        .header("Idempotency-Key", "refund-key-1"))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", startsWith("/api/refunds/")))
                .andExpect(jsonPath("$.status").value("REQUESTED"))
                .andReturn().getResponse().getContentAsString();
        mockMvc.perform(json(as(merchantAKey, post("/api/refunds/payment-intents/" + captured.getId())), refundBody)
                        .header("Idempotency-Key", "refund-key-1"))
                .andExpect(status().isOk())
                .andExpect(header().string("Idempotent-Replayed", "true"));
        String refundId = objectMapper.readTree(created).get("id").asText();
        mockMvc.perform(as(merchantAKey, post("/api/refunds/" + refundId + "/cancel")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"));

        PaymentIntent reconciled = reconciledIntent("ORDER-9008", "1000");
        mockMvc.perform(json(as(merchantAKey, post("/api/refunds/payment-intents/" + reconciled.getId())), refundBody)
                        .header("Idempotency-Key", "refund-key-2"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.status").value("PENDING_REVIEW"));
    }

    @Test
    void shouldReturnConsistentErrorsForMissingParametersAndUnknownRoutes() throws Exception {
        mockMvc.perform(as(OPS_ANA_KEY, post("/api/settlements/batches")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        mockMvc.perform(as(OPS_ANA_KEY, get("/api/does-not-exist")))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("ROUTE_NOT_FOUND"));
        mockMvc.perform(as(OPS_ANA_KEY, get("/api/audit-events")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        mockMvc.perform(as(OPS_ANA_KEY, get("/api/operations/summary")).accept(MediaType.APPLICATION_XML))
                .andExpect(status().isNotAcceptable());
    }

    @Test
    void shouldCreateSettlementBatchesWith201And409WhenNothingIsLeft() throws Exception {
        capturedIntent("ORDER-9009", "1000");

        mockMvc.perform(as(OPS_ANA_KEY, post("/api/settlements/batches?merchantId=" + MERCHANT_A)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.transactionCount").value(1))
                .andExpect(jsonPath("$.totalAmount").value(1000.0));
        mockMvc.perform(as(OPS_ANA_KEY, post("/api/settlements/batches?merchantId=" + MERCHANT_A)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("NOTHING_TO_SETTLE"));
    }

    @Test
    void shouldBoundListsFilterBankLinesAndKeepPersistenceDetailsOutOfResponses() throws Exception {
        paidIntent("ORDER-LIST-1", "3000");
        paidIntent("ORDER-LIST-2", "2000");
        var batch = settle(MERCHANT_A);
        landPayouts();
        String reference = reload(batch).getPayoutReference();

        mockMvc.perform(as(OPS_ANA_KEY, get("/api/bank/lines").param("endToEndId", reference)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].endToEndId").value(reference))
                .andExpect(jsonPath("$[0].matchStatus").value("MATCHED"));
        mockMvc.perform(as(OPS_ANA_KEY, get("/api/bank/lines").param("endToEndId", reference).param("status", "SUSPENSE")))
                .andExpect(jsonPath("$.length()").value(0));
        mockMvc.perform(as(OPS_ANA_KEY, get("/api/bank/statements").param("limit", "1")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(1));
        mockMvc.perform(as(OPS_ANA_KEY, get("/api/settlements/batches").param("merchantId", MERCHANT_A).param("limit", "1")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].payoutStatus").value("LANDED"))
                .andExpect(jsonPath("$[0].new").doesNotExist());
        mockMvc.perform(as(OPS_ANA_KEY, get("/api/outbox").param("status", "SUCCEEDED").param("limit", "5")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(1));

        for (String limit : new String[]{"0", "1001", "-3"}) {
            mockMvc.perform(as(OPS_ANA_KEY, get("/api/bank/lines").param("limit", limit)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        }
    }
}
