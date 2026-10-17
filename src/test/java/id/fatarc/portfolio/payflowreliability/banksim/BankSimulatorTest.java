package id.fatarc.portfolio.payflowreliability.banksim;

import id.fatarc.portfolio.payflowreliability.support.IntegrationTest;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** The simulated bank books credits on their value date and issues one camt.053 statement per closed business day. */
class BankSimulatorTest extends IntegrationTest {

    private LocalDate today() {
        return LocalDate.ofInstant(clock.instant(), ZoneId.systemDefault());
    }

    private void credit(String amount, String reference, LocalDate valueDate) {
        bankSimulator.receiveCredit("IDR", new BigDecimal(amount), reference, "PAYOUT " + reference, "Provider", valueDate);
    }

    @Test
    void shouldIssueOneStatementPerBusinessDayWithBalancesThatChain() {
        credit("2991.00", "PFBREF1", today().plusDays(1));
        credit("100.00", "PFBREF2", today().plusDays(2));

        List<BankSimStatement> statements = bankSimulator.closeDays("IDR", today().plusDays(2));

        assertThat(statements).extracting(BankSimStatement::getSequenceNumber).containsExactly(1L, 2L, 3L);
        assertThat(statements).extracting(BankSimStatement::getStatementDate)
                .containsExactly(today(), today().plusDays(1), today().plusDays(2));
        assertThat(statements.get(0).getContent()).doesNotContain("<Ntry>").contains("<Cd>CLBD</Cd></CdOrPrtry></Tp><Amt Ccy=\"IDR\">0.00</Amt>");
        assertThat(statements.get(1).getContent()).contains("<EndToEndId>PFBREF1</EndToEndId>")
                .contains("<Cd>CLBD</Cd></CdOrPrtry></Tp><Amt Ccy=\"IDR\">2991.00</Amt>");
        assertThat(statements.get(2).getContent()).contains("<Cd>OPBD</Cd></CdOrPrtry></Tp><Amt Ccy=\"IDR\">2991.00</Amt>")
                .contains("<Cd>CLBD</Cd></CdOrPrtry></Tp><Amt Ccy=\"IDR\">3091.00</Amt>");
        assertThat(bankSimulator.businessDate("IDR")).isEqualTo(today().plusDays(3));
    }

    @Test
    void shouldApplyEachInjectedFaultToTheNextCredit() {
        bankFaults.inject(BankFaults.Mode.DROP_CREDIT, 1, null, null);
        bankFaults.inject(BankFaults.Mode.DUPLICATE_CREDIT, 1, null, null);
        bankFaults.inject(BankFaults.Mode.NET_OF_FEES, 1, null, new BigDecimal("6.50"));
        bankFaults.inject(BankFaults.Mode.WRONG_REFERENCE, 1, null, null);
        bankFaults.inject(BankFaults.Mode.MISSING_REFERENCE, 1, null, null);
        bankFaults.inject(BankFaults.Mode.LATE_CREDIT, 1, 3, null);
        bankFaults.inject(BankFaults.Mode.RETURN_PAYOUT, 1, 1, null);
        LocalDate value = today().plusDays(1);

        credit("10.00", "PFBDROP", value);
        credit("20.00", "PFBDUP", value);
        credit("30.00", "PFBNET", value);
        credit("40.00", "PFBWRONG", value);
        credit("50.00", "PFBMISSING", value);
        credit("60.00", "PFBLATE", value);
        credit("70.00", "PFBRETURN", value);

        assertThat(bankSimulator.entriesFor("PFBDROP")).isEmpty();
        assertThat(bankSimulator.entriesFor("PFBDUP")).hasSize(2)
                .extracting(BankSimEntry::getAccountServicerRef).doesNotHaveDuplicates();
        assertThat(bankSimulator.entriesFor("PFBNET")).singleElement().satisfies(entry -> {
            assertThat(entry.getAmount()).isEqualByComparingTo("23.50");
            assertThat(entry.getChargesAmount()).isEqualByComparingTo("6.50");
            assertThat(entry.getInstructedAmount()).isEqualByComparingTo("30.00");
        });
        assertThat(bankSimulator.entriesFor("PFBWRONG")).isEmpty();
        assertThat(bankSimulator.entriesFor("PFBMISSING")).isEmpty();
        assertThat(bankSimulator.entriesFor("PFBLATE")).singleElement()
                .satisfies(entry -> assertThat(entry.getValueDate()).isEqualTo(value.plusDays(3)));
        assertThat(bankSimulator.entriesFor("PFBRETURN")).extracting(BankSimEntry::getCreditDebit, BankSimEntry::isReversal)
                .containsExactly(org.assertj.core.groups.Tuple.tuple("CRDT", false), org.assertj.core.groups.Tuple.tuple("DBIT", true));
    }

    @Test
    void shouldListAReSentStatementTwiceInTheFeed() {
        bankFaults.inject(BankFaults.Mode.RESEND_STATEMENT, 1, null, null);
        List<BankSimStatement> issued = bankSimulator.closeDays("IDR", today().plusDays(1));

        assertThat(bankSimulator.statementsAfter(issued.getFirst().getAccountId(), 0))
                .extracting(BankSimStatement::getStatementId)
                .containsExactly(issued.get(0).getStatementId(), issued.get(0).getStatementId(), issued.get(1).getStatementId());
    }
}
