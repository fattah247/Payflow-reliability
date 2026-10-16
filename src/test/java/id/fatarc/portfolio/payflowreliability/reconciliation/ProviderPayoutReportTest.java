package id.fatarc.portfolio.payflowreliability.reconciliation;

import com.fasterxml.jackson.databind.JsonNode;
import id.fatarc.portfolio.payflowreliability.payment.PaymentIntent;
import id.fatarc.portfolio.payflowreliability.payment.PaymentState;
import id.fatarc.portfolio.payflowreliability.reconciliation.breaks.BreakCategory;
import id.fatarc.portfolio.payflowreliability.reconciliation.breaks.ReconciliationBreak;
import id.fatarc.portfolio.payflowreliability.reconciliation.dto.ReconciliationReportRequest;
import id.fatarc.portfolio.payflowreliability.reconciliation.dto.ReconciliationReportResponse;
import id.fatarc.portfolio.payflowreliability.reconciliation.dto.ReconciliationReportResponse.PayoutOutcome;
import id.fatarc.portfolio.payflowreliability.reconciliation.dto.ReconciliationReportResponse.RowOutcome;
import id.fatarc.portfolio.payflowreliability.settlement.SettlementBatch;
import id.fatarc.portfolio.payflowreliability.support.IntegrationTest;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** The provider's leg of the three-way match per payout: what its settlement report says about gross, fee and net. */
class ProviderPayoutReportTest extends IntegrationTest {
    private static final String PROVIDER_KEY = "sim_test_provider_key";

    private SettlementBatch inTransit(String reference) {
        paidIntent(reference, "3000");
        SettlementBatch batch = settle(MERCHANT_A);
        drainOutbox();
        return reload(batch);
    }

    private ReconciliationReportResponse report(LocalDate date, ReconciliationReportRequest.PayoutRow... payouts) {
        return reconciliationService.importReport(new ReconciliationReportRequest(date, null, List.of(), List.of(payouts)), OPS_ANA);
    }

    private static ReconciliationReportRequest.PayoutRow row(SettlementBatch batch, String gross, String fee, String net) {
        return new ReconciliationReportRequest.PayoutRow(batch.getPayoutReference(), batch.getProviderPayoutId(),
                new BigDecimal(gross), new BigDecimal(fee), new BigDecimal(net), "IDR", batch.getExpectedValueDate());
    }

    @Test
    void shouldAcceptAPayoutRowThatAgreesWithTheLedgerAndTheProvidersAnswer() {
        SettlementBatch batch = inTransit("ORDER-PR-1");

        ReconciliationReportResponse result = report(null, row(batch, "3000.00", "9.00", "2991.00"));
        ReconciliationReportResponse again = report(null, row(batch, "3000.00", "9.00", "2991.00"));

        assertThat(result.payouts()).singleElement().extracting(ReconciliationReportResponse.PayoutResult::outcome)
                .isEqualTo(PayoutOutcome.CONSISTENT);
        assertThat(again.payouts()).singleElement().extracting(ReconciliationReportResponse.PayoutResult::outcome)
                .isEqualTo(PayoutOutcome.ALREADY_REPORTED);
        assertThat(openBreaks()).isEmpty();
    }

    @Test
    void shouldOpenABreakWhenTheProviderReportsADifferentFee() {
        SettlementBatch batch = inTransit("ORDER-PR-2");

        ReconciliationReportResponse result = report(null, row(batch, "3000.00", "12.00", "2988.00"));

        assertThat(result.payouts()).singleElement().satisfies(payout -> {
            assertThat(payout.outcome()).isEqualTo(PayoutOutcome.MISMATCH);
            assertThat(payout.detail()).contains("fee 12.00, the provider answered 9.00").contains("net 2988.00");
        });
        assertThat(openBreaks()).extracting(ReconciliationBreak::getCategory).containsExactly(BreakCategory.PROVIDER_PAYOUT_MISMATCH);
    }

    @Test
    void shouldFlagUnknownAndMissingPayouts() {
        SettlementBatch batch = inTransit("ORDER-PR-3");
        LocalDate today = LocalDate.ofInstant(batch.getCreatedAt(), ZoneId.systemDefault());

        ReconciliationReportResponse result = report(today, new ReconciliationReportRequest.PayoutRow(
                "PFB00000000000000000000000000000000", "sim_po_unknown", new BigDecimal("10"), BigDecimal.ZERO,
                new BigDecimal("10"), "IDR", today));

        assertThat(result.payouts()).extracting(ReconciliationReportResponse.PayoutResult::outcome)
                .containsExactly(PayoutOutcome.UNKNOWN_REFERENCE, PayoutOutcome.MISSING_FROM_REPORT);
        assertThat(openBreaks()).extracting(ReconciliationBreak::getCategory)
                .containsExactlyInAnyOrder(BreakCategory.UNKNOWN_PROVIDER_PAYOUT, BreakCategory.MISSING_FROM_PROVIDER_REPORT);
    }

    @Test
    void shouldReconcileEndToEndFromTheProvidersOwnReportAndTheBanksOwnStatement() throws Exception {
        PaymentIntent first = paidIntent("ORDER-PR-4", "1000");
        PaymentIntent second = paidIntent("ORDER-PR-5", "2000");
        SettlementBatch batch = settle(MERCHANT_A);
        landPayouts();
        LocalDate today = LocalDate.ofInstant(batch.getCreatedAt(), ZoneId.systemDefault());

        // Download the provider's report as an operator would, and upload it unchanged.
        String report = mockMvc.perform(get("/provider-sim/v1/reports/settlement?date=" + today)
                        .header("Authorization", "Bearer " + PROVIDER_KEY))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        JsonNode result = body(mockMvc.perform(json(as(OPS_ANA_KEY, post("/api/reconciliation/reports")), report))
                .andExpect(status().isOk()));

        assertThat(result.path("reconciledCount").asInt()).isEqualTo(2);
        assertThat(result.path("payouts").get(0).path("outcome").asText()).isEqualTo(PayoutOutcome.CONSISTENT.name());
        assertThat(stateOf(first)).isEqualTo(PaymentState.RECONCILED);
        assertThat(stateOf(second)).isEqualTo(PaymentState.RECONCILED);
        assertThat(openBreaks()).isEmpty();

        JsonNode caseFile = body(mockMvc.perform(as(OPS_ANA_KEY, get("/api/payment-intents/" + first.getId() + "/case-file"))));
        assertThat(caseFile.path("certainty").findValuesAsText("stage"))
                .containsExactly("AUTHORIZED", "CAPTURED", "PAID_OUT_BY_PROVIDER", "LANDED_IN_BANK", "RECONCILED");
        assertThat(caseFile.path("certainty").findValuesAsText("known")).containsOnly("true");
        assertThat(caseFile.path("certainty").get(3).path("evidence").asText()).contains("bank line").contains("statement");
        assertThat(ledgerInvariantService.check().violations()).isEmpty();
        assertThat(RowOutcome.values()).contains(RowOutcome.MATCHED_AWAITING_BANK);
    }
}
