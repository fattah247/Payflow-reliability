package id.fatarc.portfolio.payflowreliability.reconciliation;

import com.fasterxml.jackson.databind.JsonNode;
import id.fatarc.portfolio.payflowreliability.bank.BankLineStatus;
import id.fatarc.portfolio.payflowreliability.bank.BankStatementLineRepository;
import id.fatarc.portfolio.payflowreliability.bank.StatementImportResult;
import id.fatarc.portfolio.payflowreliability.banksim.BankFaults;
import id.fatarc.portfolio.payflowreliability.banksim.BankSimStatement;
import id.fatarc.portfolio.payflowreliability.ledger.LedgerAccounts;
import id.fatarc.portfolio.payflowreliability.ledger.dto.JournalView;
import id.fatarc.portfolio.payflowreliability.reconciliation.breaks.*;
import id.fatarc.portfolio.payflowreliability.settlement.BrokenReason;
import id.fatarc.portfolio.payflowreliability.settlement.PayoutStatus;
import id.fatarc.portfolio.payflowreliability.settlement.SettlementBatch;
import id.fatarc.portfolio.payflowreliability.support.CamtFixture;
import id.fatarc.portfolio.payflowreliability.support.IntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Ledger, provider and bank must agree about every payout. Each bank fault ends in exactly one place: landed,
 * or in suspense with a break that says why, who owns it and when it is due. After every test the ledger check
 * proves that bank credits equal payouts minus fees, plus what open breaks hold, to the cent.
 */
class ThreeWayReconciliationTest extends IntegrationTest {
    @Autowired
    private BreakResolutionService breakResolutionService;
    @Autowired
    private BankStatementLineRepository lineRepository;

    @AfterEach
    void booksStillAddUp() {
        assertThat(ledgerInvariantService.check().violations()).isEmpty();
    }

    /** A captured 3000.00 settled and accepted by the provider: fee 9.00, net 2991.00, valued tomorrow. */
    private SettlementBatch inTransit(String merchantId, String reference) {
        paidIntent(merchantId, reference, "3000", "IDR");
        SettlementBatch batch = settle(merchantId);
        drainOutbox();
        return reload(batch);
    }

    private SettlementBatch inTransit(String reference) {
        return inTransit(MERCHANT_A, reference);
    }

    private BigDecimal balance(String account) {
        return ledgerQueryService.balances(null, "IDR").stream().filter(row -> row.account().equals(account))
                .map(row -> row.balance()).findFirst().orElse(BigDecimal.ZERO);
    }

    private ReconciliationBreak onlyOpen(BreakCategory category) {
        return openBreaks().stream().filter(open -> open.getCategory() == category).findFirst()
                .orElseThrow(() -> new AssertionError("no open " + category + " break in " + openBreaks()));
    }

    private void closeAndImport(LocalDate through) {
        closeBankDays("IDR", through);
        importStatements();
    }

    @Test
    void shouldLandACreditMatchedByReferenceAmountAndValueDateAndShowEveryCheck() throws Exception {
        SettlementBatch batch = inTransit("ORDER-3W-1");
        closeAndImport(batch.getExpectedValueDate());

        assertThat(reload(batch).getPayoutStatus()).isEqualTo(PayoutStatus.LANDED);
        JsonNode view = body(mockMvc.perform(as(OPS_ANA_KEY, get("/api/reconciliation/three-way/batches/" + batch.getId())))
                .andExpect(status().isOk()));
        assertThat(view.path("payoutStatus").asText()).isEqualTo("LANDED");
        assertThat(view.path("provider").path("fee").decimalValue()).isEqualByComparingTo("9.00");
        assertThat(view.path("bank").get(0).path("received").decimalValue()).isEqualByComparingTo("2991.00");
        assertThat(view.path("checks").findValuesAsText("passed")).as("only the provider report is still missing")
                .containsOnly("true", "null").contains("true");
        assertThat(openBreaks()).isEmpty();
    }

    @Test
    void shouldLandACreditThatIsLateButInsideTheWindow() {
        SettlementBatch batch = inTransit("ORDER-3W-2");
        bankFaults.inject(BankFaults.Mode.LATE_CREDIT, 1, 1, null);
        // The fault applies to the next credit, so pay out a second batch for another merchant.
        SettlementBatch late = inTransit(MERCHANT_B, "ORDER-3W-2B");

        closeAndImport(late.getExpectedValueDate().plusDays(1));

        assertThat(reload(batch).getPayoutStatus()).isEqualTo(PayoutStatus.LANDED);
        assertThat(reload(late).getPayoutStatus()).isEqualTo(PayoutStatus.LANDED);
        assertThat(reload(late).getLandedValueDate()).isEqualTo(late.getExpectedValueDate().plusDays(1));
        assertThat(openBreaks()).as("a day late is inside the two-day window").isEmpty();
    }

    @Test
    void shouldDeclareACreditMissingOnlyWhenEveryStatementThroughTheWindowHasArrived() {
        bankFaults.inject(BankFaults.Mode.DROP_CREDIT, 1, null, null);
        SettlementBatch batch = inTransit("ORDER-3W-3");

        closeAndImport(batch.getExpectedValueDate().plusDays(1));
        threeWayReconciliationService.run(OPS_ANA.name());
        assertThat(reload(batch).getPayoutStatus()).as("the window is still open").isEqualTo(PayoutStatus.IN_TRANSIT);

        closeAndImport(batch.getExpectedValueDate().plusDays(2));
        threeWayReconciliationService.run(OPS_ANA.name());

        SettlementBatch missing = reload(batch);
        assertThat(missing.getPayoutStatus()).isEqualTo(PayoutStatus.BROKEN);
        assertThat(missing.getBrokenReason()).isEqualTo(BrokenReason.MISSING_CREDIT);
        ReconciliationBreak open = onlyOpen(BreakCategory.MISSING_CREDIT);
        assertThat(open.getOwner()).isEqualTo(BreakOwner.TREASURY);
        assertThat(open.getAmount()).isEqualByComparingTo("2991.00");
        assertThat(open.getSuspenseAmount()).as("nothing arrived, nothing is held").isEqualByComparingTo("0");
        assertThat(balance(LedgerAccounts.PAYOUTS_IN_TRANSIT)).as("the money is still in transit").isEqualByComparingTo("3000");

        assertThat(breakResolutionService.get(open.getId()).overdue()).isFalse();
        clock.advance(Duration.ofHours(25));
        assertThat(breakResolutionService.get(open.getId()).overdue()).as("past its 24-hour window").isTrue();
    }

    @Test
    void shouldHoldACreditOutsideTheWindowUntilSomeoneAcceptsIt() {
        bankFaults.inject(BankFaults.Mode.LATE_CREDIT, 1, 3, null);
        SettlementBatch batch = inTransit("ORDER-3W-4");
        closeAndImport(batch.getExpectedValueDate().plusDays(2));
        threeWayReconciliationService.run(OPS_ANA.name());
        assertThat(reload(batch).getBrokenReason()).isEqualTo(BrokenReason.MISSING_CREDIT);

        closeAndImport(batch.getExpectedValueDate().plusDays(3));

        ReconciliationBreak late = onlyOpen(BreakCategory.LATE_CREDIT);
        assertThat(late.getSuspenseAmount()).isEqualByComparingTo("2991.00");
        assertThat(reload(batch).getPayoutStatus()).as("outside the window: a person decides").isEqualTo(PayoutStatus.BROKEN);
        assertThat(balance(LedgerAccounts.BANK_SUSPENSE)).isEqualByComparingTo("-2991.00");

        breakResolutionService.resolve(late.getId(), new ResolveBreakRequest(BreakAction.ASSIGN_TO_BATCH, batch.getId(),
                "Provider confirms the payout was delayed by a bank holiday."), OPS_ANA);

        assertThat(reload(batch).getPayoutStatus()).isEqualTo(PayoutStatus.LANDED);
        assertThat(openBreaks()).as("the missing-credit break closed with it").isEmpty();
        assertThat(balance(LedgerAccounts.BANK_SUSPENSE)).isEqualByComparingTo("0");
        assertThat(balance(LedgerAccounts.BANK_CASH)).isEqualByComparingTo("2991.00");
    }

    @Test
    void shouldHoldADuplicateCreditInSuspenseUntilTheBankTakesItBack() {
        bankFaults.inject(BankFaults.Mode.DUPLICATE_CREDIT, 1, null, null);
        SettlementBatch batch = inTransit("ORDER-3W-5");
        closeAndImport(batch.getExpectedValueDate());

        assertThat(reload(batch).getPayoutStatus()).isEqualTo(PayoutStatus.LANDED);
        ReconciliationBreak duplicate = onlyOpen(BreakCategory.DUPLICATE_CREDIT);
        assertThat(duplicate.getSuspenseAmount()).isEqualByComparingTo("2991.00");
        assertThat(balance(LedgerAccounts.BANK_CASH)).as("both credits are in the bank").isEqualByComparingTo("5982.00");
        assertThat(breakResolutionService.get(duplicate.getId()).allowedActions()).as("money is released only by explaining it").isEmpty();

        byte[] returned = CamtFixture.statement("STMT-RETURN-DUP", 99, "IDR", batch.getExpectedValueDate().plusDays(1))
                .returnDebit("2991.00", batch.getPayoutReference(), batch.getExpectedValueDate().plusDays(1)).bytes();
        bankStatementImportService.importStatement(returned, OPS_ANA.name());

        assertThat(openBreaks()).isEmpty();
        assertThat(reload(batch).getPayoutStatus()).as("the original credit still stands").isEqualTo(PayoutStatus.LANDED);
        assertThat(balance(LedgerAccounts.BANK_SUSPENSE)).isEqualByComparingTo("0");
        assertThat(balance(LedgerAccounts.BANK_CASH)).isEqualByComparingTo("2991.00");
    }

    @Test
    void shouldBookBankChargesAsTheirOwnLineSoGrossMinusFeesIsExactlyNet() {
        bankFaults.inject(BankFaults.Mode.NET_OF_FEES, 1, null, new BigDecimal("6.50"));
        SettlementBatch batch = inTransit("ORDER-3W-6");
        closeAndImport(batch.getExpectedValueDate());

        SettlementBatch landed = reload(batch);
        assertThat(landed.getPayoutStatus()).isEqualTo(PayoutStatus.LANDED);
        assertThat(landed.getLandedAmount()).isEqualByComparingTo("2984.50");
        assertThat(landed.getBankChargesAmount()).isEqualByComparingTo("6.50");
        JournalView journal = jdbcLanded(batch);
        assertThat(journal.postings()).extracting(JournalView.PostingView::account, posting -> posting.amount().toPlainString())
                .containsExactlyInAnyOrder(
                        org.assertj.core.groups.Tuple.tuple(LedgerAccounts.BANK_CASH, "2984.50"),
                        org.assertj.core.groups.Tuple.tuple(LedgerAccounts.PROVIDER_FEES, "9.00"),
                        org.assertj.core.groups.Tuple.tuple(LedgerAccounts.BANK_CHARGES, "6.50"),
                        org.assertj.core.groups.Tuple.tuple(LedgerAccounts.PAYOUTS_IN_TRANSIT, "-3000.00"));
    }

    private JournalView jdbcLanded(SettlementBatch batch) {
        java.util.UUID journalId = jdbcTemplate.queryForObject(
                "select id from ledger_journals where journal_type = 'PAYOUT_LANDED' and reference = ?", java.util.UUID.class,
                batch.getId().toString());
        return new JournalView(journalId, null, null, null, "IDR", null, null, null,
                jdbcTemplate.query("select account, amount from ledger_postings where journal_id = ?",
                        (row, n) -> new JournalView.PostingView(row.getString("account"),
                                row.getBigDecimal("amount").setScale(2, java.math.RoundingMode.UNNECESSARY)), journalId));
    }

    @Test
    void shouldNeverMatchByAmountAlone() {
        bankFaults.inject(BankFaults.Mode.WRONG_REFERENCE, 1, null, null);
        SettlementBatch first = inTransit(MERCHANT_A, "ORDER-3W-7A");
        SettlementBatch second = inTransit(MERCHANT_B, "ORDER-3W-7B");
        assertThat(first.getExpectedNetAmount()).isEqualByComparingTo(second.getExpectedNetAmount());

        closeAndImport(first.getExpectedValueDate());

        assertThat(reload(first).getPayoutStatus()).as("same amount, wrong reference: not landed").isEqualTo(PayoutStatus.IN_TRANSIT);
        assertThat(reload(second).getPayoutStatus()).isEqualTo(PayoutStatus.LANDED);
        ReconciliationBreak unmatched = onlyOpen(BreakCategory.UNMATCHED_CREDIT);
        assertThat(unmatched.getOwner()).isEqualTo(BreakOwner.TREASURY);
        assertThat(unmatched.getDetail()).contains("matches no payout");

        breakResolutionService.resolve(unmatched.getId(), new ResolveBreakRequest(BreakAction.ASSIGN_TO_BATCH, first.getId(),
                "The bank confirmed the transfer was for merchant A's payout; the reference was mistyped."), OPS_ANA);

        assertThat(reload(first).getPayoutStatus()).isEqualTo(PayoutStatus.LANDED);
        assertThat(openBreaks()).isEmpty();
    }

    @Test
    void shouldRefuseToAssignACreditWhoseAmountDoesNotMatch() {
        bankFaults.inject(BankFaults.Mode.MISSING_REFERENCE, 1, null, null);
        bankFaults.inject(BankFaults.Mode.DROP_CREDIT, 1, null, null);
        SettlementBatch batch = inTransit("ORDER-3W-8");
        paidIntent(MERCHANT_B, "ORDER-3W-8B", "5000", "IDR");
        SettlementBatch other = settle(MERCHANT_B);
        drainOutbox();
        closeAndImport(batch.getExpectedValueDate());

        ReconciliationBreak unmatched = onlyOpen(BreakCategory.UNMATCHED_CREDIT);
        assertThat(unmatched.getDetail()).contains("carries no reference");
        assertThatThrownBy(() -> breakResolutionService.resolve(unmatched.getId(), new ResolveBreakRequest(
                BreakAction.ASSIGN_TO_BATCH, other.getId(), "Assigned to the wrong payout"), OPS_ANA))
                .as("an operator overrides the reference, never the amount")
                .hasMessageContaining("but the provider sent 4985.00");
        assertThat(reload(other).getPayoutStatus()).isEqualTo(PayoutStatus.IN_TRANSIT);

        breakResolutionService.resolve(unmatched.getId(), new ResolveBreakRequest(BreakAction.ASSIGN_TO_BATCH, batch.getId(),
                "The bank confirmed whose transfer it was."), OPS_ANA);
        assertThat(reload(batch).getPayoutStatus()).isEqualTo(PayoutStatus.LANDED);
    }

    @Test
    void shouldHoldACreditWhoseAmountDoesNotAddUp() {
        SettlementBatch batch = inTransit("ORDER-3W-9");
        // The bank deducted 1.00 somewhere and did not itemize it.
        byte[] statement = CamtFixture.statement("STMT-SHORT-1", 1, "IDR", batch.getExpectedValueDate())
                .credit("2990.00", batch.getPayoutReference(), batch.getExpectedValueDate()).bytes();

        bankStatementImportService.importStatement(statement, OPS_ANA.name());

        ReconciliationBreak mismatch = onlyOpen(BreakCategory.AMOUNT_MISMATCH);
        assertThat(mismatch.getOwner()).isEqualTo(BreakOwner.PROVIDER_OPERATIONS);
        assertThat(mismatch.getDetail()).contains("2990.00").contains("2991.00");
        assertThat(reload(batch).getPayoutStatus()).isEqualTo(PayoutStatus.IN_TRANSIT);
        assertThat(breakResolutionService.get(mismatch.getId()).allowedActions()).isEmpty();
    }

    @Test
    void shouldPutAReturnedPayoutBackOnTheProvider() {
        bankFaults.inject(BankFaults.Mode.RETURN_PAYOUT, 1, 1, null);
        SettlementBatch batch = inTransit("ORDER-3W-10");
        closeAndImport(batch.getExpectedValueDate());
        assertThat(reload(batch).getPayoutStatus()).isEqualTo(PayoutStatus.LANDED);

        closeAndImport(batch.getExpectedValueDate().plusDays(1));

        SettlementBatch returned = reload(batch);
        assertThat(returned.getPayoutStatus()).isEqualTo(PayoutStatus.BROKEN);
        assertThat(returned.getBrokenReason()).isEqualTo(BrokenReason.RETURNED);
        assertThat(returned.getReturnedAmount()).isEqualByComparingTo("2991.00");
        assertThat(onlyOpen(BreakCategory.RETURNED_PAYOUT).getOwner()).isEqualTo(BreakOwner.TREASURY);
        assertThat(balance(LedgerAccounts.BANK_CASH)).isEqualByComparingTo("0");
        assertThat(balance(LedgerAccounts.PROVIDER_RECEIVABLE)).as("the provider holds it again").isEqualByComparingTo("2991.00");
    }

    @Test
    void shouldImportTheSameStatementTwiceWithoutBookingAnythingTwice() throws Exception {
        SettlementBatch batch = inTransit("ORDER-3W-11");
        BankSimStatement issued = bankSimulator.closeDays("IDR", batch.getExpectedValueDate()).getLast();
        byte[] xml = issued.getContent().getBytes(StandardCharsets.UTF_8);

        mockMvc.perform(as(OPS_ANA_KEY, post("/api/bank/statements")).contentType(MediaType.APPLICATION_XML).content(xml))
                .andExpect(status().isOk()).andExpect(jsonPath("$.outcome").value("IMPORTED"));
        mockMvc.perform(as(OPS_ANA_KEY, post("/api/bank/statements")).contentType(MediaType.APPLICATION_XML).content(xml))
                .andExpect(status().isOk()).andExpect(jsonPath("$.outcome").value("ALREADY_IMPORTED"))
                .andExpect(jsonPath("$.newLines").value(0));
        assertThat(balance(LedgerAccounts.BANK_CASH)).isEqualByComparingTo("2991.00");

        String altered = issued.getContent().replace("<Ustrd>PAYOUT", "<Ustrd>CHANGED PAYOUT");
        mockMvc.perform(as(OPS_ANA_KEY, post("/api/bank/statements")).contentType(MediaType.APPLICATION_XML)
                        .content(altered.getBytes(StandardCharsets.UTF_8)))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.outcome").value("CONFLICT"));
        assertThat(onlyOpen(BreakCategory.STATEMENT_CONFLICT).getOwner()).isEqualTo(BreakOwner.TREASURY);

        breakResolutionService.resolve(onlyOpen(BreakCategory.STATEMENT_CONFLICT).getId(),
                new ResolveBreakRequest(BreakAction.ACKNOWLEDGE, null, "The bank confirmed the first copy is authoritative."), OPS_ANA);

        // The next statement repeats yesterday's entry (a bank error): it is not booked twice, and treasury is told.
        String repeated = issued.getContent().replace(issued.getStatementId(), issued.getStatementId() + "-NEXT")
                .replace("<ElctrncSeqNb>" + issued.getSequenceNumber() + "<", "<ElctrncSeqNb>" + (issued.getSequenceNumber() + 1) + "<");
        StatementImportResult copy = bankStatementImportService.importStatement(repeated.getBytes(StandardCharsets.UTF_8), OPS_ANA.name());
        assertThat(copy.outcome()).isEqualTo(StatementImportResult.Outcome.IMPORTED);
        assertThat(copy.newLines()).isZero();
        assertThat(copy.alreadyKnownLines()).isEqualTo(1);
        assertThat(balance(LedgerAccounts.BANK_CASH)).isEqualByComparingTo("2991.00");
        assertThat(openBreaks()).extracting(ReconciliationBreak::getDetail)
                .anySatisfy(detail -> assertThat(detail).contains("repeats 1 entry"))
                .anySatisfy(detail -> assertThat(detail).contains("balance chain is broken"));
    }

    @Test
    void shouldIgnoreAStatementTheBankDeliversTwice() {
        bankFaults.inject(BankFaults.Mode.RESEND_STATEMENT, 1, null, null);
        SettlementBatch batch = inTransit("ORDER-3W-12");
        closeBankDays("IDR", batch.getExpectedValueDate());

        List<StatementImportResult> results = importStatements();

        assertThat(results).extracting(StatementImportResult::outcome)
                .containsExactly(StatementImportResult.Outcome.IMPORTED, StatementImportResult.Outcome.ALREADY_IMPORTED,
                        StatementImportResult.Outcome.IMPORTED);
        assertThat(reload(batch).getPayoutStatus()).isEqualTo(PayoutStatus.LANDED);
    }

    @Test
    void shouldBookStatementsTheSameWhateverOrderTheyArriveIn() {
        SettlementBatch batch = inTransit("ORDER-3W-13");
        List<BankSimStatement> issued = bankSimulator.closeDays("IDR", batch.getExpectedValueDate().plusDays(1));
        assertThat(issued).hasSize(3);

        StatementImportResult third = bankStatementImportService.importStatement(issued.get(2).getContent().getBytes(StandardCharsets.UTF_8), OPS_ANA.name());
        assertThat(third.coveredThrough()).as("statements 1 and 2 have not arrived").isNull();
        StatementImportResult first = bankStatementImportService.importStatement(issued.get(0).getContent().getBytes(StandardCharsets.UTF_8), OPS_ANA.name());
        assertThat(first.coveredSequence()).isEqualTo(1);
        threeWayReconciliationService.run(OPS_ANA.name());
        assertThat(reload(batch).getPayoutStatus()).as("a gap is not a missing credit").isEqualTo(PayoutStatus.IN_TRANSIT);

        StatementImportResult second = bankStatementImportService.importStatement(issued.get(1).getContent().getBytes(StandardCharsets.UTF_8), OPS_ANA.name());

        assertThat(second.coveredSequence()).isEqualTo(3);
        assertThat(second.landed()).containsExactly(batch.getPayoutReference());
        assertThat(reload(batch).getPayoutStatus()).isEqualTo(PayoutStatus.LANDED);
        assertThat(balance(LedgerAccounts.BANK_CASH)).isEqualByComparingTo("2991.00");
    }

    @Test
    void shouldMatchACreditThatArrivedBeforeTheProviderConfirmed() {
        paidIntent("ORDER-3W-14", "3000");
        SettlementBatch batch = settle(MERCHANT_A);
        providerClient.crashAfterNextCall();
        assertThatThrownBy(() -> outboxWorker.runOnce()).isInstanceOf(Error.class);
        // The provider paid and the bank credited, but we never recorded the provider's answer.
        closeAndImport(LocalDate.ofInstant(clock.instant(), java.time.ZoneId.systemDefault()).plusDays(1));
        assertThat(onlyOpen(BreakCategory.UNMATCHED_CREDIT).getDetail()).contains("has not confirmed");
        assertThat(lineRepository.findByMatchStatusOrderByImportedAtAsc(BankLineStatus.SUSPENSE)).hasSize(1);

        clock.advance(Duration.ofSeconds(3));
        drainOutbox();
        threeWayReconciliationService.run(OPS_ANA.name());

        assertThat(reload(batch).getPayoutStatus()).isEqualTo(PayoutStatus.LANDED);
        assertThat(openBreaks()).isEmpty();
        assertThat(lineRepository.findByMatchStatusOrderByImportedAtAsc(BankLineStatus.CLEARED)).hasSize(1);
    }

    @Test
    void shouldListBreaksWithOwnerAgeAndAllowedActionsOverHttp() throws Exception {
        bankFaults.inject(BankFaults.Mode.MISSING_REFERENCE, 1, null, null);
        SettlementBatch batch = inTransit("ORDER-3W-15");
        closeAndImport(batch.getExpectedValueDate());
        ReconciliationBreak open = onlyOpen(BreakCategory.UNMATCHED_CREDIT);

        mockMvc.perform(as(OPS_ANA_KEY, get("/api/reconciliation/breaks?status=OPEN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].category").value("UNMATCHED_CREDIT"))
                .andExpect(jsonPath("$[0].owner").value("TREASURY"))
                .andExpect(jsonPath("$[0].overdue").value(false))
                .andExpect(jsonPath("$[0].allowedActions[0]").value("ASSIGN_TO_BATCH"));
        mockMvc.perform(json(as(OPS_ANA_KEY, post("/api/reconciliation/breaks/" + open.getId() + "/resolve")),
                        "{\"action\": \"ACKNOWLEDGE\", \"note\": \"looks fine\"}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("BREAK_ACTION_NOT_APPLICABLE"));
        mockMvc.perform(json(as(OPS_ANA_KEY, post("/api/reconciliation/breaks/" + open.getId() + "/resolve")),
                        "{\"action\": \"ASSIGN_TO_BATCH\", \"batchId\": \"" + batch.getId() + "\", \"note\": \"bank confirmed\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("RESOLVED"))
                .andExpect(jsonPath("$.resolution").value("ASSIGNED_TO_BATCH"));
    }
}
