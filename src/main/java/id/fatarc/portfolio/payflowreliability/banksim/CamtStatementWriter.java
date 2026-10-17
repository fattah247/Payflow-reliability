package id.fatarc.portfolio.payflowreliability.banksim;

import id.fatarc.portfolio.payflowreliability.common.Money;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/**
 * Writes an end-of-day statement in the ISO 20022 camt.053.001.08 subset documented in
 * docs/settlement-certainty.md: group header, one statement with account, opening and closing booked
 * balances, and one entry per booking with its references, value date, instructed amount and charges.
 */
final class CamtStatementWriter {
    static final String NAMESPACE = "urn:iso:std:iso:20022:tech:xsd:camt.053.001.08";

    private CamtStatementWriter() {
    }

    static String write(String statementId, long sequence, String accountId, String currency, LocalDate date,
                        BigDecimal opening, BigDecimal closing, List<BankSimEntry> entries, Instant createdAt) {
        StringBuilder xml = new StringBuilder();
        xml.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
        xml.append("<Document xmlns=\"").append(NAMESPACE).append("\">\n");
        xml.append("  <BkToCstmrStmt>\n");
        xml.append("    <GrpHdr><MsgId>").append(escape("MSG-" + statementId)).append("</MsgId><CreDtTm>")
                .append(createdAt).append("</CreDtTm></GrpHdr>\n");
        xml.append("    <Stmt>\n");
        xml.append("      <Id>").append(escape(statementId)).append("</Id>\n");
        xml.append("      <ElctrncSeqNb>").append(sequence).append("</ElctrncSeqNb>\n");
        xml.append("      <CreDtTm>").append(createdAt).append("</CreDtTm>\n");
        xml.append("      <Acct><Id><Othr><Id>").append(escape(accountId)).append("</Id></Othr></Id><Ccy>")
                .append(currency).append("</Ccy></Acct>\n");
        balance(xml, "OPBD", opening, currency, date);
        balance(xml, "CLBD", closing, currency, date);
        for (BankSimEntry entry : entries) {
            entry(xml, entry);
        }
        xml.append("    </Stmt>\n");
        xml.append("  </BkToCstmrStmt>\n");
        xml.append("</Document>\n");
        return xml.toString();
    }

    private static void balance(StringBuilder xml, String type, BigDecimal amount, String currency, LocalDate date) {
        xml.append("      <Bal><Tp><CdOrPrtry><Cd>").append(type).append("</Cd></CdOrPrtry></Tp>")
                .append(amount(amount.abs(), currency))
                .append("<CdtDbtInd>").append(amount.signum() < 0 ? "DBIT" : "CRDT").append("</CdtDbtInd>")
                .append("<Dt><Dt>").append(date).append("</Dt></Dt></Bal>\n");
    }

    private static void entry(StringBuilder xml, BankSimEntry entry) {
        String currency = entry.getCurrency();
        xml.append("      <Ntry>\n");
        xml.append("        ").append(amount(entry.getAmount(), currency)).append('\n');
        xml.append("        <CdtDbtInd>").append(entry.getCreditDebit()).append("</CdtDbtInd>\n");
        xml.append("        <RvslInd>").append(entry.isReversal()).append("</RvslInd>\n");
        xml.append("        <Sts><Cd>BOOK</Cd></Sts>\n");
        xml.append("        <BookgDt><Dt>").append(entry.getBookingDate()).append("</Dt></BookgDt>\n");
        xml.append("        <ValDt><Dt>").append(entry.getValueDate()).append("</Dt></ValDt>\n");
        xml.append("        <AcctSvcrRef>").append(escape(entry.getAccountServicerRef())).append("</AcctSvcrRef>\n");
        xml.append("        <BkTxCd><Domn><Cd>PMNT</Cd><Fmly><Cd>RCDT</Cd><SubFmlyCd>")
                .append(entry.isReversal() ? "RRTN" : "ESCT").append("</SubFmlyCd></Fmly></Domn></BkTxCd>\n");
        if (entry.getChargesAmount() != null && entry.getChargesAmount().signum() != 0) {
            xml.append("        <Chrgs><TtlChrgsAndTaxAmt Ccy=\"").append(currency).append("\">")
                    .append(plain(entry.getChargesAmount(), currency)).append("</TtlChrgsAndTaxAmt><Rcrd>")
                    .append(amount(entry.getChargesAmount(), currency))
                    .append("<CdtDbtInd>DBIT</CdtDbtInd><ChrgInclInd>true</ChrgInclInd></Rcrd></Chrgs>\n");
        }
        xml.append("        <NtryDtls><TxDtls>\n");
        xml.append("          <Refs><AcctSvcrRef>").append(escape(entry.getAccountServicerRef())).append("</AcctSvcrRef>")
                .append("<EndToEndId>").append(escape(entry.getEndToEndId() == null ? "NOTPROVIDED" : entry.getEndToEndId()))
                .append("</EndToEndId></Refs>\n");
        if (entry.getInstructedAmount() != null) {
            xml.append("          <AmtDtls><InstdAmt>").append(amount(entry.getInstructedAmount(), currency))
                    .append("</InstdAmt></AmtDtls>\n");
        }
        if (entry.getDebtorName() != null) {
            xml.append("          <RltdPties><Dbtr><Nm>").append(escape(entry.getDebtorName())).append("</Nm></Dbtr></RltdPties>\n");
        }
        if (entry.getRemittanceInfo() != null) {
            xml.append("          <RmtInf><Ustrd>").append(escape(entry.getRemittanceInfo())).append("</Ustrd></RmtInf>\n");
        }
        xml.append("        </TxDtls></NtryDtls>\n");
        xml.append("      </Ntry>\n");
    }

    private static String amount(BigDecimal amount, String currency) {
        return "<Amt Ccy=\"" + currency + "\">" + plain(amount, currency) + "</Amt>";
    }

    private static String plain(BigDecimal amount, String currency) {
        return Money.atScale(amount, currency).toPlainString();
    }

    private static String escape(String value) {
        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }
}
