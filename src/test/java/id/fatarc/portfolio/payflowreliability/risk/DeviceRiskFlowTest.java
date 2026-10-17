package id.fatarc.portfolio.payflowreliability.risk;

import id.fatarc.portfolio.payflowreliability.audit.AuditEvent;
import id.fatarc.portfolio.payflowreliability.audit.AuditHash;
import id.fatarc.portfolio.payflowreliability.common.BusinessException;
import id.fatarc.portfolio.payflowreliability.ledger.JournalType;
import id.fatarc.portfolio.payflowreliability.operations.MoneyAtRiskService;
import id.fatarc.portfolio.payflowreliability.payment.CancellationReason;
import id.fatarc.portfolio.payflowreliability.payment.PaymentIntent;
import id.fatarc.portfolio.payflowreliability.payment.PaymentState;
import id.fatarc.portfolio.payflowreliability.payment.ProviderStatus;
import id.fatarc.portfolio.payflowreliability.payment.WebhookOutcome;
import id.fatarc.portfolio.payflowreliability.payment.dto.CreatePaymentIntentRequest;
import id.fatarc.portfolio.payflowreliability.review.ReviewReason;
import id.fatarc.portfolio.payflowreliability.support.IntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Device risk on payment creation, end to end: allowed payments start as before, a step-up holds the payment until the
 * customer confirms (or it expires), a block is final and never reaches the provider, and every decision is in the
 * payment's audit chain with the rule and the points behind it.
 */
class DeviceRiskFlowTest extends IntegrationTest {
    private static final RiskEvidence CLEAN = evidence(true);
    private static final RiskEvidence EMULATOR = evidence(true, "emulator_fingerprint", "EMULATOR", SignalSeverity.MEDIUM);
    private static final RiskEvidence ROOTED = evidence(true, "root_package", "ROOT", SignalSeverity.HIGH);
    private static final String ROOTED_JSON = """
            {"deviceKeyId":"device-7","level":"LOW","simulated":false,"stepUpKeyAvailable":true,
             "signals":[{"id":"root_package","category":"ROOT","severity":"HIGH","source":"DEVICE_CHECK"}]}""";

    @Autowired
    private RiskService riskService;
    @Autowired
    private MoneyAtRiskService moneyAtRiskService;

    private static RiskEvidence evidence(boolean stepUpKey, Object... signal) {
        List<RiskEvidence.Signal> signals = signal.length == 0 ? List.of()
                : List.of(new RiskEvidence.Signal((String) signal[0], (String) signal[1], (SignalSeverity) signal[2], SignalSource.DEVICE_CHECK));
        return new RiskEvidence("device-1", null, false, stepUpKey, signals);
    }

    private PaymentIntent create(String reference, String amount, RiskEvidence evidence) {
        return paymentIntentService.create(new CreatePaymentIntentRequest(null, reference, new BigDecimal(amount), "IDR",
                RiskAction.PHYSICAL_GOODS, evidence), "key-" + reference, AS_MERCHANT_A).intent();
    }

    private RiskDecision decisionOf(PaymentIntent intent) {
        return riskService.decisionFor(intent.getId()).orElseThrow();
    }

    private boolean reachedProvider(PaymentIntent intent) {
        return reload(intent).getStartedAt() != null || providerSimulator.payment(intent.getId()).isPresent();
    }

    @Test
    void shouldLetACleanDeviceThroughAndStillRecordWhy() {
        PaymentIntent intent = create("RISK-OK-1", "150000", CLEAN);

        assertThat(intent.getState()).isEqualTo(PaymentState.CREATED);
        RiskDecision decision = decisionOf(intent);
        assertThat(decision.getDecision()).isEqualTo(RiskDecisionType.ALLOW);
        assertThat(decision.getRule()).isEqualTo(RiskRule.BELOW_THRESHOLDS);
        assertThat(decision.getOutcome()).isEqualTo(RiskOutcome.ALLOWED);
        assertThat(auditFor(intent)).filteredOn(event -> event.getEventType().equals("RISK_DECISION"))
                .singleElement().extracting(AuditEvent::getMetadata).asString()
                .contains("decision=ALLOW", "rule=BELOW_THRESHOLDS", "points=0");

        assertThat(paymentIntentService.start(intent.getId(), AS_MERCHANT_A).getState()).isEqualTo(PaymentState.PENDING);
    }

    @Test
    void shouldHoldASteppedUpPaymentUntilTheCustomerConfirms() throws Exception {
        PaymentIntent intent = create("RISK-STEP-1", "200000", EMULATOR);
        assertThat(intent.getState()).isEqualTo(PaymentState.REQUIRES_CONFIRMATION);
        assertThat(intent.getRequiredConfirmation()).isEqualTo(RequiredConfirmation.STEP_UP_KEY);

        mockMvc.perform(as(merchantAKey, post("/api/payment-intents/{id}/start", intent.getId())))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("STEP_UP_REQUIRED"));
        assertThat(reachedProvider(intent)).isFalse();

        mockMvc.perform(json(as(merchantAKey, post("/api/payment-intents/{id}/confirm", intent.getId())), "{\"method\":\"TAP\"}"))
                .andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.code").value("CONFIRMATION_METHOD_MISMATCH"));
        mockMvc.perform(json(as(OPS_ANA_KEY, post("/api/payment-intents/{id}/confirm", intent.getId())), "{\"method\":\"STEP_UP_KEY\"}"))
                .andExpect(status().isForbidden());

        mockMvc.perform(json(as(merchantAKey, post("/api/payment-intents/{id}/confirm", intent.getId())), "{\"method\":\"STEP_UP_KEY\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.state").value("CREATED"))
                .andExpect(jsonPath("$.confirmedAt").isNotEmpty());
        mockMvc.perform(json(as(merchantAKey, post("/api/payment-intents/{id}/confirm", intent.getId())), "{\"method\":\"STEP_UP_KEY\"}"))
                .andExpect(status().isOk());
        assertThat(decisionOf(intent).getOutcome()).isEqualTo(RiskOutcome.CONFIRMED);

        PaymentIntent paid = paidAfterStart(intent, "200000");
        assertThat(paid.getState()).isEqualTo(PaymentState.SUCCESS);
        assertThat(auditFor(intent)).extracting(AuditEvent::getNewState).containsSubsequence(
                "REQUIRES_CONFIRMATION", "CREATED", "PENDING", "SUCCESS");
        assertThat(auditChainVerifier.verify(AuditHash.chainKey(intent.getId(), null, null)).valid()).isTrue();
    }

    private PaymentIntent paidAfterStart(PaymentIntent intent, String amount) {
        paymentIntentService.start(intent.getId(), AS_MERCHANT_A);
        providerSimulator.pay(intent.getId(), new BigDecimal(amount), "IDR", null);
        deliverWebhooks();
        return reload(intent);
    }

    @Test
    void shouldExpireAStepUpNobodyConfirmedWithoutEverReachingTheProvider() {
        PaymentIntent intent = create("RISK-STEP-2", "300000", EMULATOR);
        clock.advance(Duration.ofMinutes(9));
        sweeper.runOnce();
        assertThat(stateOf(intent)).as("still inside the confirmation timeout").isEqualTo(PaymentState.REQUIRES_CONFIRMATION);

        clock.advance(Duration.ofMinutes(2));
        sweeper.runOnce();

        assertThat(stateOf(intent)).isEqualTo(PaymentState.EXPIRED);
        assertThat(decisionOf(intent).getOutcome()).isEqualTo(RiskOutcome.CONFIRMATION_EXPIRED);
        assertThat(reachedProvider(intent)).isFalse();
        assertThatThrownBy(() -> paymentIntentService.confirm(intent.getId(), RequiredConfirmation.STEP_UP_KEY, AS_MERCHANT_A))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void shouldRecordACancelledStepUpAsAbandonedFriction() {
        PaymentIntent intent = create("RISK-STEP-3", "300000", evidence(false, "app_debuggable", "DEBUGGER", SignalSeverity.MEDIUM));
        assertThat(intent.getRequiredConfirmation()).as("no step-up key on this device").isEqualTo(RequiredConfirmation.TAP);

        paymentIntentService.cancel(intent.getId(), CancellationReason.ABANDONED, AS_MERCHANT_A);

        assertThat(stateOf(intent)).isEqualTo(PaymentState.CANCELLED);
        assertThat(decisionOf(intent).getOutcome()).isEqualTo(RiskOutcome.CANCELLED_UNCONFIRMED);
        assertThat(decisionOf(intent).getOutcome().stepUpAbandoned()).isTrue();
    }

    @Test
    void shouldBlockForGoodAndNeverHandTheBlockedPaymentToTheProvider() throws Exception {
        String created = postIntent(merchantAKey, "key-RISK-BLOCK-1", """
                {"merchantReference":"RISK-BLOCK-1","amount":120000,"currency":"IDR","action":"DIGITAL_GOODS","riskEvidence":%s}"""
                .formatted(ROOTED_JSON)).andExpect(status().isCreated()).andExpect(jsonPath("$.state").value("BLOCKED"))
                .andExpect(jsonPath("$.riskAction").value("DIGITAL_GOODS"))
                .andReturn().getResponse().getContentAsString();
        PaymentIntent intent = paymentIntentRepository.findById(UUID.fromString(objectMapper.readTree(created).path("id").asText()))
                .orElseThrow();

        mockMvc.perform(as(merchantAKey, post("/api/payment-intents/{id}/start", intent.getId())))
                .andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.code").value("PAYMENT_BLOCKED"));
        mockMvc.perform(as(OPS_ANA_KEY, get("/api/risk/decisions/{id}", intent.getId())))
                .andExpect(status().isOk()).andExpect(jsonPath("$.decision").value("BLOCK"))
                .andExpect(jsonPath("$.rule").value("SEVERE_SIGNAL"))
                .andExpect(jsonPath("$.reportedLevel").value("LOW")).andExpect(jsonPath("$.deviceLevel").value("HIGH"))
                .andExpect(jsonPath("$.devicePoints").value(3)).andExpect(jsonPath("$.actionPoints").value(1));
        mockMvc.perform(as(OPS_ANA_KEY, get("/api/payment-intents/{id}/case-file", intent.getId())))
                .andExpect(status().isOk()).andExpect(jsonPath("$.riskDecision.rule").value("SEVERE_SIGNAL"))
                .andExpect(jsonPath("$.attention.waitingOn").value(containsString("never handed to the provider")));

        assertThat(reachedProvider(intent)).isFalse();
        assertThat(outboxCommandRepository.findAll()).isEmpty();
        assertThat(auditFor(intent)).filteredOn(event -> "BLOCKED".equals(event.getNewState())).singleElement()
                .extracting(AuditEvent::getMetadata).asString().contains("rule=SEVERE_SIGNAL", "signals=root_package:ROOT:HIGH:DEVICE_CHECK");
        MoneyAtRiskService.Item blocked = moneyAtRiskService.compute().get(MoneyAtRiskService.Kind.BLOCKED_TODAY);
        assertThat(blocked.count()).isEqualTo(1);
        assertThat(blocked.amount().get("IDR")).isEqualByComparingTo("120000");
    }

    @Test
    void shouldAnswerARetryWithTheFirstDecisionWhateverEvidenceItCarries() throws Exception {
        String rooted = """
                {"merchantReference":"RISK-RETRY-1","amount":90000,"currency":"IDR","riskEvidence":%s}""".formatted(ROOTED_JSON);
        String cleaned = """
                {"merchantReference":"RISK-RETRY-1","amount":90000,"currency":"IDR",
                 "riskEvidence":{"level":"LOW","simulated":false,"stepUpKeyAvailable":true,"signals":[]}}""";
        String first = postIntent(merchantAKey, "key-RISK-RETRY-1", rooted).andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();

        postIntent(merchantAKey, "key-RISK-RETRY-1", cleaned).andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(objectMapper.readTree(first).path("id").asText()))
                .andExpect(jsonPath("$.state").value("BLOCKED"));
        postIntent(merchantAKey, "key-RISK-RETRY-1", rooted.replace("\"currency\":\"IDR\"", "\"currency\":\"IDR\",\"action\":\"STORED_VALUE\""))
                .andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REUSED"));
        assertThat(paymentIntentRepository.count()).isEqualTo(1);
    }

    @Test
    void shouldSendACaptureForABlockedOrUnconfirmedPaymentToReviewInsteadOfApplyingIt() {
        PaymentIntent blocked = create("RISK-ROGUE-1", "50000", ROOTED);
        PaymentIntent waiting = create("RISK-ROGUE-2", "60000", EMULATOR);

        // A provider that charges anyway (a stale page, a misbehaving integration) is a contradiction, not a payment.
        assertThat(webhook(blocked.getId(), "evt-rogue-1", "tx-rogue-1", ProviderStatus.SUCCESS, "50000").outcome())
                .isEqualTo(WebhookOutcome.SENT_TO_REVIEW);
        assertThat(webhook(waiting.getId(), "evt-rogue-2", "tx-rogue-2", ProviderStatus.SUCCESS, "60000").outcome())
                .isEqualTo(WebhookOutcome.SENT_TO_REVIEW);

        assertThat(stateOf(blocked)).isEqualTo(PaymentState.BLOCKED);
        assertThat(stateOf(waiting)).isEqualTo(PaymentState.REQUIRES_CONFIRMATION);
        assertThat(reload(blocked).getCapturedAt()).isNull();
        assertThat(reload(waiting).getCapturedAt()).isNull();
        assertThat(ledgerJournalRepository.findAll()).noneMatch(journal -> journal.getJournalType() == JournalType.CAPTURE);
        assertThat(openReviewReasons()).containsExactlyInAnyOrder(ReviewReason.INVALID_STATE_TRANSITION, ReviewReason.INVALID_STATE_TRANSITION);
    }

    @Test
    void shouldShowStepUpsWaitingForConfirmationAsMoneyAtRisk() {
        create("RISK-WAIT-1", "100000", EMULATOR);
        create("RISK-WAIT-2", "250000", EMULATOR);
        create("RISK-WAIT-3", "70000", CLEAN);

        MoneyAtRiskService.Item awaiting = moneyAtRiskService.compute().get(MoneyAtRiskService.Kind.AWAITING_CONFIRMATION);
        assertThat(awaiting.count()).isEqualTo(2);
        assertThat(awaiting.amount().get("IDR")).isEqualByComparingTo("350000");
    }

    @Test
    void shouldRejectMalformedEvidence() throws Exception {
        postIntent(merchantAKey, "key-RISK-BAD-1", """
                {"merchantReference":"RISK-BAD-1","amount":1000,"currency":"IDR",
                 "riskEvidence":{"simulated":false,"stepUpKeyAvailable":true,
                  "signals":[{"id":"Root Package!","category":"ROOT","severity":"HIGH","source":"DEVICE_CHECK"}]}}""")
                .andExpect(status().isBadRequest());
        assertThat(paymentIntentRepository.count()).isZero();
    }

    @Test
    void shouldLetOperatorsRecordWhatAPaymentTurnedOutToBe() throws Exception {
        PaymentIntent intent = create("RISK-LABEL-1", "80000", CLEAN);

        mockMvc.perform(json(as(OPS_ANA_KEY, post("/api/risk/outcomes")), """
                        {"outcomes":[{"paymentIntentId":"%s","label":"FRAUD","source":"CHARGEBACK"}]}""".formatted(intent.getId())))
                .andExpect(status().isOk()).andExpect(jsonPath("$.labelled").value(1));
        mockMvc.perform(json(as(merchantAKey, post("/api/risk/outcomes")), """
                        {"outcomes":[{"paymentIntentId":"%s","label":"LEGITIMATE","source":"CHARGEBACK"}]}""".formatted(intent.getId())))
                .andExpect(status().isForbidden());

        assertThat(decisionOf(intent).getLabel()).isEqualTo(RiskLabel.FRAUD);
        assertThat(decisionOf(intent).getLabelSource()).isEqualTo(RiskLabel.Source.CHARGEBACK);
        assertThat(auditFor(intent)).extracting(AuditEvent::getEventType).contains("RISK_LABELLED");
    }

    @Test
    void shouldRecordALabelWhileTheProvidersWebhookArrivesForTheSamePayment() throws Exception {
        // Labels arrive whenever they arrive; the provider does not wait. Both append to the payment's audit chain.
        for (int i = 0; i < 12; i++) {
            PaymentIntent intent = paymentIntentService.start(create("RISK-RACE-" + i, "40000", CLEAN).getId(), AS_MERCHANT_A);
            java.util.concurrent.atomic.AtomicInteger turn = new java.util.concurrent.atomic.AtomicInteger();
            String eventId = "evt-race-" + i;
            String transaction = "tx-race-" + i;
            List<Object> outcomes = race(2, () -> turn.getAndIncrement() == 0
                    ? riskService.label(intent.getId(), RiskLabel.LEGITIMATE, RiskLabel.Source.CUSTOMER_REPORT, "operator:ops-ana")
                    : webhook(intent.getId(), eventId, transaction, ProviderStatus.SUCCESS, "40000"));

            assertThat(outcomes).as("round %d", i).noneMatch(outcome -> outcome instanceof Exception);
            assertThat(stateOf(intent)).isEqualTo(PaymentState.SUCCESS);
            assertThat(decisionOf(intent).getLabel()).isEqualTo(RiskLabel.LEGITIMATE);
            assertThat(auditChainVerifier.verify(AuditHash.chainKey(intent.getId(), null, null)).valid()).isTrue();
        }
    }
}
