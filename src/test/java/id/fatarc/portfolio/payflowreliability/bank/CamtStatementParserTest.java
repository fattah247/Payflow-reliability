package id.fatarc.portfolio.payflowreliability.bank;

import id.fatarc.portfolio.payflowreliability.common.BusinessException;
import id.fatarc.portfolio.payflowreliability.common.ErrorCode;
import id.fatarc.portfolio.payflowreliability.support.CamtFixture;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Statement files come from outside: read what we rely on, refuse anything that does not add up. */
class CamtStatementParserTest {
    private static final LocalDate DAY = LocalDate.of(2026, 10, 16);

    @Test
    void shouldReadBalancesEntriesReferencesAndItemizedCharges() {
        byte[] xml = CamtFixture.statement("STMT-IDR-1", 7, "IDR", DAY).opening("100.00")
                .credit("2984.50", "PFBREF1", DAY.plusDays(1), "6.50", "2991.00")
                .returnDebit("50.00", "PFBREF2", DAY)
                .credit("10.00", null, DAY)
                .bytes();

        CamtStatement statement = CamtStatementParser.parse(xml);

        assertThat(statement.statementId()).isEqualTo("STMT-IDR-1");
        assertThat(statement.sequenceNumber()).isEqualTo(7);
        assertThat(statement.accountId()).isEqualTo("PAYFLOW-SETTLE-IDR");
        assertThat(statement.openingBalance()).isEqualByComparingTo("100.00");
        assertThat(statement.closingBalance()).isEqualByComparingTo("3044.50");
        assertThat(statement.entries()).hasSize(3);
        CamtStatement.Entry net = statement.entries().getFirst();
        assertThat(net.amount()).isEqualByComparingTo("2984.50");
        assertThat(net.chargesAmount()).isEqualByComparingTo("6.50");
        assertThat(net.instructedAmount()).isEqualByComparingTo("2991.00");
        assertThat(net.valueDate()).isEqualTo(DAY.plusDays(1));
        assertThat(statement.entries().get(1).reversal()).isTrue();
        assertThat(statement.entries().get(1).signedAmount()).isEqualByComparingTo("-50.00");
        assertThat(statement.entries().get(2).endToEndId()).as("NOTPROVIDED means no reference").isNull();
    }

    @Test
    void shouldRefuseAStatementThatDoesNotAddUp() {
        byte[] xml = CamtFixture.statement("STMT-IDR-2", 1, "IDR", DAY).credit("10.00", "R", DAY).closing("11.00").bytes();

        assertThatThrownBy(() -> CamtStatementParser.parse(xml))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("does not add up")
                .extracting("code").isEqualTo(ErrorCode.BANK_STATEMENT_INVALID);
    }

    @Test
    void shouldRefuseMorePrecisionThanTheCurrencyHas() {
        byte[] xml = CamtFixture.statement("STMT-JPY-1", 1, "JPY", DAY).credit("10.5", "R", DAY).bytes();

        assertThatThrownBy(() -> CamtStatementParser.parse(xml)).hasMessageContaining("more decimals than JPY allows");
    }

    @Test
    void shouldNeverResolveEntitiesInAFileFromOutside() {
        String xxe = "<?xml version=\"1.0\"?><!DOCTYPE d [<!ENTITY x SYSTEM \"file:///etc/passwd\">]>"
                + "<Document xmlns=\"urn:iso:std:iso:20022:tech:xsd:camt.053.001.08\">&x;</Document>";

        assertThatThrownBy(() -> CamtStatementParser.parse(xxe.getBytes(StandardCharsets.UTF_8)))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("not well-formed");
    }

    @Test
    void shouldRefuseOtherDocumentsAndGarbage() {
        assertThatThrownBy(() -> CamtStatementParser.parse("<Document xmlns=\"urn:other\"/>".getBytes(StandardCharsets.UTF_8)))
                .hasMessageContaining("not a camt.053");
        assertThatThrownBy(() -> CamtStatementParser.parse("{}".getBytes(StandardCharsets.UTF_8)))
                .hasMessageContaining("not well-formed");
    }
}
