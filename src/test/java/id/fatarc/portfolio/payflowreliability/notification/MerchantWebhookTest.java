package id.fatarc.portfolio.payflowreliability.notification;

import com.fasterxml.jackson.databind.JsonNode;
import id.fatarc.portfolio.payflowreliability.merchantsim.MerchantSimulator;
import id.fatarc.portfolio.payflowreliability.merchantsim.MerchantSimulator.Outcome;
import id.fatarc.portfolio.payflowreliability.operations.MoneyInvariantService;
import id.fatarc.portfolio.payflowreliability.payment.CancellationReason;
import id.fatarc.portfolio.payflowreliability.payment.PaymentIntent;
import id.fatarc.portfolio.payflowreliability.payment.PaymentState;
import id.fatarc.portfolio.payflowreliability.provider.LocalServer;
import id.fatarc.portfolio.payflowreliability.refund.RefundStatus;
import id.fatarc.portfolio.payflowreliability.support.IntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Shops are told what happened to their payments and refunds: every change is written as an event in the transaction
 * of the change, delivered over real HTTP to a simulated shop that verifies the signature, processes each event once and
 * applies a payment's events in order. Failures are retried with backoff, given up on after the last attempt, and
 * redeliverable; the events list is the record a shop catches up from.
 */
class MerchantWebhookTest extends IntegrationTest {
    private static final String SHOP = "shop-a";

    @Autowired
    private LocalServer localServer;

    @Autowired
    private MoneyInvariantService moneyInvariantService;

    /** Registers merchant A's endpoint at the simulated shop, and gives the shop the secret it was shown. */
    private String registerShop() throws Exception {
        JsonNode endpoint = body(mockMvc.perform(json(as(merchantAKey, put("/api/webhook-endpoint")),
                        "{\"url\":\"" + shopUrl() + "\"}"))
                .andExpect(status().isCreated()));
        String secret = endpoint.path("secret").asText();
        merchantSimulator.setSecret(SHOP, secret);
        return secret;
    }

    private String shopUrl() {
        return localServer.baseUrl() + "/merchant-sim/v1/shops/" + SHOP + "/webhooks";
    }

    private List<MerchantEvent> eventsOf(PaymentIntent intent) {
        return merchantEventRepository.findByPaymentIntentIdOrderBySequenceAsc(intent.getId());
    }

    private MerchantWebhookDispatcher.RunReport runLater() {
        clock.advance(Duration.ofSeconds(1));
        return merchantWebhookDispatcher.runOnce();
    }

    private List<Outcome> outcomes() {
        return merchantSimulator.deliveries(SHOP).stream().map(MerchantSimulator.Delivery::outcome).toList();
    }

    @Test
    void shouldTellTheShopEveryChangeSignedInOrderAndOnce() throws Exception {
        String secret = registerShop();
        assertThat(secret).startsWith("whsec_").hasSizeGreaterThan(40);

        PaymentIntent paid = paidIntent("WH-PAID", "150000");
        List<MerchantEvent> events = eventsOf(paid);
        assertThat(events).extracting(MerchantEvent::getSequence).containsExactly(1L, 2L, 3L);
        assertThat(events).extracting(MerchantEvent::getEventType)
                .containsExactly(MerchantEvent.PAYMENT_CREATED, MerchantEvent.PAYMENT_UPDATED, MerchantEvent.PAYMENT_UPDATED);
        assertThat(events).extracting(MerchantEvent::getState).containsExactly("CREATED", "PENDING", "SUCCESS");
        assertThat(events).allSatisfy(event -> assertThat(event.getStatus()).isEqualTo(MerchantEvent.Status.PENDING));

        var report = merchantWebhookDispatcher.runOnce();

        assertThat(report.delivered()).isEqualTo(3);
        assertThat(outcomes()).containsExactly(Outcome.APPLIED, Outcome.APPLIED, Outcome.APPLIED);
        assertThat(merchantSimulator.payments(SHOP).get(paid.getId().toString()))
                .isEqualTo(new MerchantSimulator.Applied("SUCCESS", 3));
        assertThat(eventsOf(paid)).allSatisfy(event -> {
            assertThat(event.getStatus()).isEqualTo(MerchantEvent.Status.DELIVERED);
            assertThat(event.getLastStatusCode()).isEqualTo(200);
        });

        JsonNode listed = body(mockMvc.perform(as(merchantAKey, get("/api/events").param("paymentIntentId", paid.getId().toString())))
                .andExpect(status().isOk()));
        assertThat(listed).hasSize(3);
        JsonNode last = listed.get(2).path("payload");
        assertThat(last.path("type").asText()).isEqualTo("payment.updated");
        assertThat(last.path("sequence").asLong()).isEqualTo(3);
        assertThat(last.path("data").path("state").asText()).isEqualTo("SUCCESS");
        assertThat(last.path("data").path("previousState").asText()).isEqualTo("PENDING");
        assertThat(last.path("data").path("amount").asText()).as("money as a decimal string").isEqualTo("150000.00");
        assertThat(last.path("data").path("merchantReference").asText()).isEqualTo("WH-PAID");

        assertThat(moneyInvariantService.check().rules()).filteredOn(rule -> rule.name().equals("PAYMENT_CHANGES_ANNOUNCED"))
                .singleElement().satisfies(rule -> assertThat(rule.holds()).as(String.join("; ", rule.violations())).isTrue());
    }

    @Test
    void shouldAnnounceRefundsAndPutOvertakenDeliveriesBackInOrder() throws Exception {
        registerShop();
        PaymentIntent paid = paidIntent("WH-REFUND", "80000");
        merchantWebhookDispatcher.runOnce();

        var refund = requestRefund(paid, "30000", "wh-refund-1");
        // The shop is briefly down for the refund's first event; the later ones arrive before it is retried.
        merchantSimulator.inject(SHOP, 1, 503, 0, 0);
        drainOutbox();
        deliverWebhooks();
        assertThat(reload(refund).getStatus()).isEqualTo(RefundStatus.COMPLETED);
        merchantWebhookDispatcher.runOnce();
        runLater();

        List<MerchantSimulator.Delivery> refundDeliveries = merchantSimulator.deliveries(SHOP).stream()
                .filter(delivery -> delivery.type().startsWith("refund.")).toList();
        assertThat(refundDeliveries).extracting(MerchantSimulator.Delivery::outcome)
                .as("created fails, completed applies, the retried created is older than what the shop has")
                .containsExactly(Outcome.FAULT, Outcome.APPLIED, Outcome.STALE);
        assertThat(merchantSimulator.view(SHOP).get("refunds").toString()).contains("COMPLETED");
        assertThat(merchantEventRepository.findByPaymentIntentIdOrderBySequenceAsc(paid.getId()))
                .filteredOn(event -> event.getRefundId() != null)
                .extracting(MerchantEvent::getEventType, MerchantEvent::getState)
                .containsExactly(org.assertj.core.groups.Tuple.tuple(MerchantEvent.REFUND_CREATED, "REQUESTED"),
                        org.assertj.core.groups.Tuple.tuple(MerchantEvent.REFUND_UPDATED, "COMPLETED"));
    }

    @Test
    void shouldRetryErrorsRedirectsAndSlowAnswersWithBackoffUntilTheShopAnswers() throws Exception {
        registerShop();
        PaymentIntent intent = createIntent("WH-RETRY", "1000");
        MerchantEvent created = eventsOf(intent).getFirst();

        merchantSimulator.inject(SHOP, 2, 302, 0, 0);
        assertThat(merchantWebhookDispatcher.runOnce().retrying()).isEqualTo(1);
        MerchantEvent afterFirst = merchantEventRepository.findById(created.getId()).orElseThrow();
        assertThat(afterFirst.getLastStatusCode()).as("a redirect is not followed").isEqualTo(302);
        assertThat(afterFirst.getLastError()).isEqualTo("The endpoint answered HTTP 302.");
        assertThat(afterFirst.getNextAttemptAt()).as("retried after a backoff").isAfter(afterFirst.getCreatedAt());
        assertThat(runLater().retrying()).isEqualTo(1);

        // The last attempt: the shop processes it but answers after our read timeout, so to us it failed.
        merchantSimulator.inject(SHOP, 0, null, 1, 800);
        assertThat(runLater().failed()).isEqualTo(1);
        MerchantEvent afterSlow = merchantEventRepository.findById(created.getId()).orElseThrow();
        assertThat(afterSlow.getLastError()).isEqualTo("No answer within 500 ms.");
        assertThat(afterSlow.getAttempts()).isEqualTo(3);

        // Sent again, the shop recognizes the event id and acknowledges it without applying it twice.
        mockMvc.perform(as(merchantAKey, post("/api/events/" + created.getId() + "/redeliver"))).andExpect(status().isOk());
        assertThat(merchantWebhookDispatcher.runOnce().delivered()).isEqualTo(1);
        assertThat(outcomes()).containsExactly(Outcome.FAULT, Outcome.FAULT, Outcome.APPLIED, Outcome.DUPLICATE);
        assertThat(merchantEventRepository.findById(created.getId()).orElseThrow().getStatus()).isEqualTo(MerchantEvent.Status.DELIVERED);
    }

    @Test
    void shouldGiveUpAfterTheLastAttemptAndSendAgainWhenAsked() throws Exception {
        registerShop();
        PaymentIntent intent = createIntent("WH-DEAD", "1000");
        UUID eventId = eventsOf(intent).getFirst().getId();
        merchantSimulator.inject(SHOP, 3, 500, 0, 0);

        merchantWebhookDispatcher.runOnce();
        runLater();
        assertThat(runLater().failed()).isEqualTo(1);
        mockMvc.perform(as(merchantAKey, get("/api/events/" + eventId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("FAILED"))
                .andExpect(jsonPath("$.attempts").value(3))
                .andExpect(jsonPath("$.lastError").value("The endpoint answered HTTP 500."));
        mockMvc.perform(as(OPS_ANA_KEY, get("/api/events").param("status", "FAILED")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(1));
        assertThat(runLater().attempted()).as("a failed event is not retried on its own").isZero();

        mockMvc.perform(as(merchantAKey, post("/api/events/" + eventId + "/redeliver")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("PENDING")).andExpect(jsonPath("$.attempts").value(0));
        assertThat(merchantWebhookDispatcher.runOnce().delivered()).isEqualTo(1);
        assertThat(outcomes()).endsWith(Outcome.APPLIED);
    }

    @Test
    void shouldKeepVerifyingThroughASecretRotation() throws Exception {
        String original = registerShop();

        JsonNode rotated = body(mockMvc.perform(as(merchantAKey, post("/api/webhook-endpoint/rotate-secret"))).andExpect(status().isOk()));
        String next = rotated.path("secret").asText();
        assertThat(next).startsWith("whsec_").isNotEqualTo(original);
        assertThat(rotated.path("previousSecretSignsUntil").asText()).isNotBlank();
        mockMvc.perform(as(merchantAKey, get("/api/webhook-endpoint")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.secret").doesNotExist());

        createIntent("WH-ROT-1", "1000");
        merchantWebhookDispatcher.runOnce();
        assertThat(merchantSimulator.deliveries(SHOP).getLast())
                .as("the shop still holds the old secret; the delivery carries both signatures")
                .satisfies(delivery -> {
                    assertThat(delivery.signatures()).isEqualTo(2);
                    assertThat(delivery.outcome()).isEqualTo(Outcome.APPLIED);
                });

        clock.advance(Duration.ofHours(25));
        createIntent("WH-ROT-2", "1000");
        merchantWebhookDispatcher.runOnce();
        assertThat(merchantSimulator.deliveries(SHOP).getLast().outcome())
                .as("after the overlap only the new secret signs, and a shop that never switched refuses it")
                .isEqualTo(Outcome.BAD_SIGNATURE);

        merchantSimulator.setSecret(SHOP, next);
        runLater();
        assertThat(merchantSimulator.deliveries(SHOP).getLast()).satisfies(delivery -> {
            assertThat(delivery.signatures()).isEqualTo(1);
            assertThat(delivery.outcome()).isEqualTo(Outcome.APPLIED);
        });
    }

    @Test
    void shouldWriteAnEventOnlyWithItsChangeAndKeepItReadableWithoutAnEndpoint() throws Exception {
        PaymentIntent noEndpoint = createIntent(MERCHANT_B, "WH-B", "1000", "IDR");
        assertThat(merchantEventRepository.findByPaymentIntentIdOrderBySequenceAsc(noEndpoint.getId()))
                .singleElement().satisfies(event -> assertThat(event.getStatus()).isEqualTo(MerchantEvent.Status.SKIPPED));
        mockMvc.perform(as(merchantBKey, get("/api/events"))).andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(1));
        assertThat(merchantWebhookDispatcher.runOnce().attempted()).isZero();

        registerShop();
        PaymentIntent intent = createIntent("WH-ROLLBACK", "1000");
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            paymentIntentService.cancel(intent.getId(), CancellationReason.CUSTOMER_REQUEST, AS_MERCHANT_A);
            status.setRollbackOnly();
        });
        assertThat(stateOf(intent)).isEqualTo(PaymentState.CREATED);
        assertThat(eventsOf(intent)).as("the cancellation rolled back, and so did its event").hasSize(1);

        mockMvc.perform(as(merchantAKey, delete("/api/webhook-endpoint")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.enabled").value(false));
        merchantWebhookDispatcher.runOnce();
        assertThat(merchantEventRepository.findById(eventsOf(intent).getFirst().getId()).orElseThrow().getStatus())
                .as("switched off before delivery").isEqualTo(MerchantEvent.Status.SKIPPED);
        mockMvc.perform(as(merchantAKey, post("/api/events/" + eventsOf(intent).getFirst().getId() + "/redeliver")))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("WEBHOOK_ENDPOINT_NOT_FOUND"));

        JsonNode moved = body(mockMvc.perform(json(as(merchantAKey, put("/api/webhook-endpoint")), "{\"url\":\"" + shopUrl() + "\"}"))
                .andExpect(status().isOk()));
        assertThat(moved.path("enabled").asBoolean()).isTrue();
        assertThat(moved.has("secret")).as("moving keeps the secret, and never shows it again").isFalse();
    }

    @Test
    void shouldPageEventsOldestFirstAndKeepEachShopToItsOwn() throws Exception {
        registerShop();
        List<UUID> created = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            created.add(createIntent("WH-PAGE-" + i, "1000").getId());
        }
        PaymentIntent other = createIntent(MERCHANT_B, "WH-OTHER", "1000", "IDR");

        List<UUID> seen = new ArrayList<>();
        String after = null;
        do {
            var request = get("/api/events").param("limit", "2");
            if (after != null) {
                request.param("after", after);
            }
            var response = mockMvc.perform(as(merchantAKey, request)).andExpect(status().isOk()).andReturn().getResponse();
            objectMapper.readTree(response.getContentAsString())
                    .forEach(event -> seen.add(UUID.fromString(event.path("paymentIntentId").asText())));
            after = response.getHeader("X-Next-After");
        } while (after != null);
        assertThat(seen).as("oldest first, every one once, only merchant A's").containsExactlyElementsOf(created);

        UUID otherEvent = merchantEventRepository.findByPaymentIntentIdOrderBySequenceAsc(other.getId()).getFirst().getId();
        mockMvc.perform(as(merchantAKey, get("/api/events/" + otherEvent)))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("MERCHANT_EVENT_NOT_FOUND"));
        mockMvc.perform(as(merchantAKey, get("/api/events").param("merchantId", MERCHANT_B))).andExpect(status().isForbidden());
        mockMvc.perform(as(OPS_ANA_KEY, get("/api/events").param("merchantId", MERCHANT_B)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(1));
        mockMvc.perform(as(merchantAKey, get("/api/events").param("from", clock.instant().plusSeconds(60).toString())))
                .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(0));
    }

    private MoneyInvariantService.Rule announcementRule() {
        return moneyInvariantService.check().rules().stream().filter(rule -> rule.name().equals("PAYMENT_CHANGES_ANNOUNCED"))
                .findFirst().orElseThrow();
    }

    @Test
    void shouldCatchAChangeTheShopWasNeverTold() {
        PaymentIntent quiet = createIntent("WH-INV-1", "1000");
        PaymentIntent paid = paidIntent("WH-INV-2", "2000");
        assertThat(announcementRule().holds()).isTrue();

        // What a bug would leave behind: a state written without the audited transition, and an event that went missing.
        jdbcTemplate.update("update payment_intents set state = 'PENDING', started_at = ? where id = ?",
                java.sql.Timestamp.from(clock.instant()), quiet.getId());
        jdbcTemplate.update("delete from merchant_events where payment_intent_id = ? and sequence = 2", paid.getId());

        MoneyInvariantService.Rule rule = announcementRule();
        assertThat(rule.holds()).isFalse();
        assertThat(rule.violations()).hasSize(2)
                .anySatisfy(violation -> assertThat(violation).contains(quiet.getId().toString(), "is PENDING", "announces another state"))
                .anySatisfy(violation -> assertThat(violation).contains(paid.getId().toString(), "changed state 2 times but announced 1"));
    }

    @Test
    void shouldRefuseEndpointsThatAreNotSafeToSendTo() throws Exception {
        mockMvc.perform(json(as(merchantAKey, put("/api/webhook-endpoint")), "{\"url\":\"ftp://shop.example/hooks\"}"))
                .andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.code").value("WEBHOOK_URL_NOT_ALLOWED"));
        mockMvc.perform(json(as(merchantAKey, put("/api/webhook-endpoint")), "{\"url\":\"https://user:pass@shop.example/hooks\"}"))
                .andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.code").value("WEBHOOK_URL_NOT_ALLOWED"));
        mockMvc.perform(json(as(merchantAKey, put("/api/webhook-endpoint")), "{\"url\":\"\"}"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(json(as(OPS_ANA_KEY, put("/api/webhook-endpoint")), "{\"url\":\"https://shop.example/hooks\"}"))
                .andExpect(status().isForbidden());
        mockMvc.perform(as(merchantAKey, get("/api/webhook-endpoint")))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("WEBHOOK_ENDPOINT_NOT_FOUND"));
        mockMvc.perform(as(merchantAKey, post("/api/merchant-webhooks/run"))).andExpect(status().isForbidden());
        mockMvc.perform(as(OPS_ANA_KEY, post("/api/merchant-webhooks/run"))).andExpect(status().isOk());
    }
}
