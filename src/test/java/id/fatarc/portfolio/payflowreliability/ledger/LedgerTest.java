package id.fatarc.portfolio.payflowreliability.ledger;

import id.fatarc.portfolio.payflowreliability.ledger.dto.InvariantReport;
import id.fatarc.portfolio.payflowreliability.payment.PaymentIntent;
import id.fatarc.portfolio.payflowreliability.payment.ProviderStatus;
import id.fatarc.portfolio.payflowreliability.support.IntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Every money movement is a balanced journal, and the books are checked against operational records. */
class LedgerTest extends IntegrationTest {
    @Autowired
    private LedgerService ledgerService;

    private void fullLifecycle() {
        PaymentIntent refunded = paidIntent("ORDER-L-1", "100000");
        requestRefund(refunded, "25000", "refund-l-1");
        drainOutbox();
        settle(MERCHANT_A);
        PaymentIntent reversed = paidIntent("ORDER-L-2", "40000");
        providerSimulator.chargeback(reversed.getId(), null);
        deliverWebhooks();
        drainOutbox();
        paidIntent(MERCHANT_B, "ORDER-L-3", "12.50", "USD");
    }

    @Test
    void shouldKeepEveryJournalBalancedAndTheBooksConsistent() {
        fullLifecycle();

        assertThat(jdbcTemplate.queryForList(
                "select journal_id from ledger_postings group by journal_id having sum(amount) <> 0", UUID.class)).isEmpty();
        assertThat(ledgerJournalRepository.findAll()).extracting("journalType").containsExactlyInAnyOrder(
                JournalType.CAPTURE, JournalType.REFUND, JournalType.PAYOUT, JournalType.CAPTURE, JournalType.REVERSAL,
                JournalType.CAPTURE);

        InvariantReport report = ledgerInvariantService.check();
        assertThat(report.violations()).isEmpty();
        assertThat(report.merchantBalancesChecked()).isEqualTo(2);
        assertThat(ledgerInvariantService.lastReport()).isEqualTo(report);
    }

    @Test
    void shouldShowWhatIsOwedToEachMerchant() {
        fullLifecycle();

        assertThat(ledgerQueryService.balances(MERCHANT_A, "IDR")).singleElement()
                .satisfies(balance -> assertThat(balance.owedToMerchant()).as("reversed payment was never paid out")
                        .isEqualByComparingTo("0"));
        assertThat(ledgerQueryService.balances(MERCHANT_B, "USD")).singleElement()
                .satisfies(balance -> assertThat(balance.owedToMerchant()).isEqualTo(new BigDecimal("12.50")));
        assertThat(ledgerQueryService.balances(null, "IDR"))
                .filteredOn(balance -> balance.account().equals(LedgerAccounts.PROVIDER_RECEIVABLE)).singleElement()
                .satisfies(balance -> assertThat(balance.balance()).isEqualByComparingTo("0"));
    }

    @Test
    void shouldRefuseUnbalancedOrDuplicateJournals() {
        PaymentIntent intent = capturedIntent("ORDER-L-4", "1000");
        TransactionTemplate tx = new TransactionTemplate(transactionManager);

        assertThatThrownBy(() -> tx.executeWithoutResult(status -> ledgerService.post(JournalType.CAPTURE, "unbalanced",
                null, "IDR", "test", List.of(new LedgerService.Posting("a", new BigDecimal("1")),
                        new LedgerService.Posting("b", new BigDecimal("-2"))))))
                .isInstanceOf(IllegalArgumentException.class);
        Boolean posted = tx.execute(status -> ledgerService.post(JournalType.CAPTURE, intent.getId().toString(), intent.getId(),
                "IDR", "retry", List.of(new LedgerService.Posting("a", BigDecimal.ONE), new LedgerService.Posting("b", BigDecimal.ONE.negate()))));

        assertThat(posted).as("same (type, reference) is posted once").isFalse();
        assertThat(ledgerJournalRepository.count()).isEqualTo(1);
    }

    @Test
    void shouldDetectBooksThatDisagreeWithOperations() {
        capturedIntent("ORDER-L-5", "1000");
        // A payout nobody recorded as a settlement batch.
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> ledgerService.payout(UUID.randomUUID(),
                MERCHANT_A, "IDR", new BigDecimal("400")));

        InvariantReport report = ledgerInvariantService.check();

        assertThat(report.healthy()).isFalse();
        assertThat(report.violations()).singleElement().satisfies(violation -> {
            assertThat(violation.kind()).isEqualTo("MERCHANT_BALANCE_MISMATCH");
            assertThat(violation.expected()).isEqualByComparingTo("1000");
            assertThat(violation.actual()).isEqualByComparingTo("600");
        });
    }

    @Test
    void shouldDetectAPayoutThatDoesNotMatchWhatItSettles() {
        PaymentIntent intent = capturedIntent("ORDER-L-7", "1000");
        settle(MERCHANT_A);
        // The balance check alone cannot see this: both sides use the batch total.
        jdbcTemplate.update("update settlement_batch_items set amount = 900 where payment_intent_id = ?", intent.getId());

        assertThat(ledgerInvariantService.check().violations()).extracting(InvariantReport.Violation::kind)
                .containsOnly("SETTLEMENT_MISMATCH").hasSize(2);
    }

    @Test
    void shouldDetectAnUnbalancedJournalWrittenAroundTheService() {
        UUID journalId = UUID.randomUUID();
        Timestamp now = Timestamp.from(clock.instant());
        jdbcTemplate.update("insert into ledger_journals (id, journal_type, reference, currency, description, created_at)"
                + " values (?, 'CAPTURE', 'manual-insert', 'IDR', 'bypassed the service', ?)", journalId, now);
        jdbcTemplate.update("insert into ledger_postings (id, journal_id, account, currency, amount, created_at)"
                + " values (?, ?, 'provider_receivable', 'IDR', 5, ?)", UUID.randomUUID(), journalId, now);

        assertThat(ledgerInvariantService.check().violations()).extracting(InvariantReport.Violation::kind)
                .contains("UNBALANCED_JOURNAL");
    }

    @Test
    void shouldExposeJournalsAndTheInvariantCheckToOperators() throws Exception {
        PaymentIntent intent = capturedIntent("ORDER-L-6", "1000");
        webhook(intent.getId(), "evt-l-6-dup", "tx-ORDER-L-6", ProviderStatus.SUCCESS, "1000");

        mockMvc.perform(as(OPS_ANA_KEY, get("/api/ledger/journals").param("paymentIntentId", intent.getId().toString())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].postings.length()").value(2));
        mockMvc.perform(as(OPS_ANA_KEY, get("/api/ledger/invariants")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.healthy").value(true));
    }
}
