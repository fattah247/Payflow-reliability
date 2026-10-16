package id.fatarc.portfolio.payflowreliability.settlement;

import id.fatarc.portfolio.payflowreliability.bank.StatementImportResult;
import id.fatarc.portfolio.payflowreliability.ledger.JournalType;
import id.fatarc.portfolio.payflowreliability.ledger.LedgerAccounts;
import id.fatarc.portfolio.payflowreliability.outbox.OutboxCommand;
import id.fatarc.portfolio.payflowreliability.providersim.SimApiRequest;
import id.fatarc.portfolio.payflowreliability.providersim.SimFaults;
import id.fatarc.portfolio.payflowreliability.reconciliation.breaks.BreakCategory;
import id.fatarc.portfolio.payflowreliability.reconciliation.breaks.BreakOwner;
import id.fatarc.portfolio.payflowreliability.support.CrashableProviderClient;
import id.fatarc.portfolio.payflowreliability.support.IntegrationTest;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneId;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.contains;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * A payout's money from batch to bank: SENT when the batch is created, IN_TRANSIT on the provider's word,
 * LANDED only on a matched bank credit. The provider deducts a 30 bps fee, booked as its own line.
 */
class PayoutLifecycleTest extends IntegrationTest {

    private BigDecimal balance(String account) {
        return ledgerQueryService.balances(null, "IDR").stream()
                .filter(row -> row.account().equals(account))
                .map(row -> row.balance())
                .findFirst().orElse(BigDecimal.ZERO);
    }

    private SettlementBatch settledBatch() {
        paidIntent("ORDER-PL-1", "1000");
        paidIntent("ORDER-PL-2", "2000");
        return settle(MERCHANT_A);
    }

    private LocalDate today() {
        return LocalDate.ofInstant(clock.instant(), ZoneId.systemDefault());
    }

    @Test
    void shouldGoFromSentToInTransitToLandedWithTheFeeOnItsOwnLine() throws Exception {
        SettlementBatch batch = settledBatch();

        assertThat(batch.getPayoutStatus()).isEqualTo(PayoutStatus.SENT);
        assertThat(batch.getPayoutReference()).isEqualTo("PFB" + batch.getId().toString().replace("-", "").toUpperCase()).hasSize(35);
        assertThat(outboxCommandRepository.findByIdempotencyKey(OutboxCommand.payoutKey(batch.getId()))).isPresent();
        assertThat(balance(LedgerAccounts.payoutsDue(MERCHANT_A))).as("committed to the merchant").isEqualByComparingTo("-3000");
        assertThat(balance(LedgerAccounts.PROVIDER_RECEIVABLE)).as("still with the provider").isEqualByComparingTo("3000");

        drainOutbox();
        SettlementBatch inTransit = reload(batch);
        assertThat(inTransit.getPayoutStatus()).isEqualTo(PayoutStatus.IN_TRANSIT);
        assertThat(inTransit.getProviderFeeAmount()).as("30 bps of 3000.00").isEqualByComparingTo("9.00");
        assertThat(inTransit.getExpectedNetAmount()).isEqualByComparingTo("2991.00");
        assertThat(inTransit.getExpectedValueDate()).isEqualTo(today().plusDays(1));
        assertThat(balance(LedgerAccounts.PAYOUTS_IN_TRANSIT)).isEqualByComparingTo("3000");
        assertThat(balance(LedgerAccounts.PROVIDER_RECEIVABLE)).isEqualByComparingTo("0");
        assertThat(balance(LedgerAccounts.BANK_CASH)).as("the provider's word is not cash").isEqualByComparingTo("0");

        closeBankDays("IDR", inTransit.getExpectedValueDate());
        assertThat(importStatements()).extracting(StatementImportResult::outcome)
                .containsOnly(StatementImportResult.Outcome.IMPORTED);

        SettlementBatch landed = reload(batch);
        assertThat(landed.getPayoutStatus()).isEqualTo(PayoutStatus.LANDED);
        assertThat(landed.getLandedAmount()).isEqualByComparingTo("2991.00");
        assertThat(balance(LedgerAccounts.BANK_CASH)).isEqualByComparingTo("2991.00");
        assertThat(balance(LedgerAccounts.PROVIDER_FEES)).isEqualByComparingTo("9.00");
        assertThat(balance(LedgerAccounts.PAYOUTS_IN_TRANSIT)).isEqualByComparingTo("0");
        assertThat(ledgerJournalRepository.existsByJournalTypeAndReference(JournalType.PAYOUT_LANDED, batch.getId().toString())).isTrue();
        assertThat(ledgerInvariantService.check().violations()).isEmpty();

        // The batch's journals, as an operator reads them: gross out of transit, net into cash, the fee on its own line.
        mockMvc.perform(as(OPS_ANA_KEY, get("/api/ledger/journals").param("reference", batch.getId().toString())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].type", contains("PAYOUT", "PAYOUT_IN_TRANSIT", "PAYOUT_LANDED")))
                .andExpect(jsonPath("$[2].postings[?(@.account == 'bank_cash')].amount", contains(2991.0)))
                .andExpect(jsonPath("$[2].postings[?(@.account == 'provider_fees')].amount", contains(9.0)))
                .andExpect(jsonPath("$[2].postings[?(@.account == 'payouts_in_transit')].amount", contains(-3000.0)));
        mockMvc.perform(as(OPS_ANA_KEY, get("/api/ledger/journals"))).andExpect(status().isBadRequest());
    }

    @Test
    void shouldNotTreatTheProvidersWordAsArrival() {
        SettlementBatch batch = settledBatch();
        drainOutbox();
        // Statements arrive, but none of them is from the value date yet.
        closeBankDays("IDR", today());
        importStatements();
        threeWayReconciliationService.run(OPS_ANA.name());

        assertThat(reload(batch).getPayoutStatus()).isEqualTo(PayoutStatus.IN_TRANSIT);
        assertThat(openBreaks()).as("not late yet: the window has not ended").isEmpty();
    }

    @Test
    void shouldBreakAPayoutTheProviderRefusesAndLeaveTheMoneyWithTheProvider() {
        SettlementBatch batch = settledBatch();
        simFaults.inject(SimFaults.Operation.PAYOUT, SimFaults.Mode.REJECT, 1);

        drainOutbox();

        SettlementBatch refused = reload(batch);
        assertThat(refused.getPayoutStatus()).isEqualTo(PayoutStatus.BROKEN);
        assertThat(refused.getBrokenReason()).isEqualTo(BrokenReason.PROVIDER_REFUSED);
        assertThat(openBreaks()).singleElement().satisfies(open -> {
            assertThat(open.getCategory()).isEqualTo(BreakCategory.PROVIDER_REFUSED_PAYOUT);
            assertThat(open.getOwner()).isEqualTo(BreakOwner.PROVIDER_OPERATIONS);
            assertThat(open.getAmount()).isEqualByComparingTo("3000.00");
        });
        assertThat(balance(LedgerAccounts.PROVIDER_RECEIVABLE)).isEqualByComparingTo("3000");
        assertThat(ledgerInvariantService.check().violations()).isEmpty();
    }

    @Test
    void shouldResendAPayoutUnderItsKeyAfterAWorkerDiesOnceTheProviderPaid() {
        SettlementBatch batch = settledBatch();
        providerClient.crashAfterNextCall();
        assertThatThrownBy(() -> outboxWorker.runOnce()).isInstanceOf(CrashableProviderClient.SimulatedCrash.class);
        assertThat(reload(batch).getPayoutStatus()).isEqualTo(PayoutStatus.SENT);

        clock.advance(Duration.ofSeconds(3));
        drainOutbox();

        assertThat(reload(batch).getPayoutStatus()).isEqualTo(PayoutStatus.IN_TRANSIT);
        assertThat(simApiRequestRepository.findByOperationOrderByReceivedAtAsc("PAYOUT"))
                .as("one request reached the provider; the next worker found it by its key")
                .extracting(SimApiRequest::getIdempotencyKey, SimApiRequest::getOutcome)
                .containsExactly(org.assertj.core.groups.Tuple.tuple("payout-" + batch.getId(), SimApiRequest.Outcome.APPLIED));
        assertThat(bankSimulator.entriesFor(batch.getPayoutReference())).as("one credit on its way").hasSize(1);
    }

    @Test
    void shouldKeepTheMerchantBalanceAtZeroAfterABatchAsBefore() {
        settledBatch();
        landPayouts();

        assertThat(ledgerQueryService.balances(MERCHANT_A, "IDR")).singleElement()
                .satisfies(row -> assertThat(row.owedToMerchant()).isEqualByComparingTo("0"));
        assertThat(balance(LedgerAccounts.payoutsDue(MERCHANT_A)))
                .as("the settlement account owes the merchant the gross; the provider fee is the platform's cost")
                .isEqualByComparingTo("-3000");
        assertThat(balance(LedgerAccounts.BANK_CASH).add(balance(LedgerAccounts.PROVIDER_FEES))).isEqualByComparingTo("3000");
    }
}
