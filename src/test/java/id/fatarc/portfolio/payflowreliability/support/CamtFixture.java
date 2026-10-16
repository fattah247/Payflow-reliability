package id.fatarc.portfolio.payflowreliability.support;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Hand-built camt.053 statements for cases the bank simulator does not produce on its own (an amount the bank
 * changed without itemizing it, an entry whose reference happens to collide, a statement that does not add up).
 */
public final class CamtFixture {
    private final String statementId;
    private final long sequence;
    private final String accountId;
    private final String currency;
    private final LocalDate date;
    private BigDecimal opening = BigDecimal.ZERO;
    private BigDecimal closingOverride;
    private BigDecimal movement = BigDecimal.ZERO;
    private final List<String> entries = new ArrayList<>();

    private CamtFixture(String statementId, long sequence, String accountId, String currency, LocalDate date) {
        this.statementId = statementId;
        this.sequence = sequence;
        this.accountId = accountId;
        this.currency = currency;
        this.date = date;
    }

    public static CamtFixture statement(String statementId, long sequence, String currency, LocalDate date) {
        return new CamtFixture(statementId, sequence, "PAYFLOW-SETTLE-" + currency, currency, date);
    }

    public CamtFixture opening(String amount) {
        this.opening = new BigDecimal(amount);
        return this;
    }

    /** Makes the statement not add up (for parser tests). */
    public CamtFixture closing(String amount) {
        this.closingOverride = new BigDecimal(amount);
        return this;
    }

    public CamtFixture credit(String amount, String endToEndId, LocalDate valueDate) {
        return entry(amount, "CRDT", false, endToEndId, valueDate, null, null);
    }

    public CamtFixture credit(String amount, String endToEndId, LocalDate valueDate, String charges, String instructed) {
        return entry(amount, "CRDT", false, endToEndId, valueDate, charges, instructed);
    }

    public CamtFixture returnDebit(String amount, String endToEndId, LocalDate valueDate) {
        return entry(amount, "DBIT", true, endToEndId, valueDate, null, null);
    }

    private CamtFixture entry(String amount, String direction, boolean reversal, String endToEndId, LocalDate valueDate,
                              String charges, String instructed) {
        BigDecimal value = new BigDecimal(amount);
        movement = movement.add("CRDT".equals(direction) ? value : value.negate());
        String servicerRef = "FIX" + UUID.randomUUID().toString().replace("-", "").substring(0, 20).toUpperCase();
        StringBuilder xml = new StringBuilder();
        xml.append("<Ntry><Amt Ccy=\"").append(currency).append("\">").append(amount).append("</Amt>")
                .append("<CdtDbtInd>").append(direction).append("</CdtDbtInd><RvslInd>").append(reversal).append("</RvslInd>")
                .append("<Sts><Cd>BOOK</Cd></Sts><BookgDt><Dt>").append(date).append("</Dt></BookgDt>")
                .append("<ValDt><Dt>").append(valueDate).append("</Dt></ValDt>")
                .append("<AcctSvcrRef>").append(servicerRef).append("</AcctSvcrRef>");
        if (charges != null) {
            xml.append("<Chrgs><TtlChrgsAndTaxAmt Ccy=\"").append(currency).append("\">").append(charges)
                    .append("</TtlChrgsAndTaxAmt><Rcrd><Amt Ccy=\"").append(currency).append("\">").append(charges)
                    .append("</Amt><CdtDbtInd>DBIT</CdtDbtInd><ChrgInclInd>true</ChrgInclInd></Rcrd></Chrgs>");
        }
        xml.append("<NtryDtls><TxDtls><Refs><AcctSvcrRef>").append(servicerRef).append("</AcctSvcrRef><EndToEndId>")
                .append(endToEndId == null ? "NOTPROVIDED" : endToEndId).append("</EndToEndId></Refs>");
        if (instructed != null) {
            xml.append("<AmtDtls><InstdAmt><Amt Ccy=\"").append(currency).append("\">").append(instructed).append("</Amt></InstdAmt></AmtDtls>");
        }
        xml.append("<RmtInf><Ustrd>PAYOUT ").append(endToEndId).append("</Ustrd></RmtInf></TxDtls></NtryDtls></Ntry>");
        entries.add(xml.toString());
        return this;
    }

    public String xml() {
        BigDecimal closing = closingOverride != null ? closingOverride : opening.add(movement);
        StringBuilder xml = new StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\"?>")
                .append("<Document xmlns=\"urn:iso:std:iso:20022:tech:xsd:camt.053.001.08\"><BkToCstmrStmt>")
                .append("<GrpHdr><MsgId>MSG-").append(statementId).append("</MsgId><CreDtTm>2026-10-16T18:00:00Z</CreDtTm></GrpHdr>")
                .append("<Stmt><Id>").append(statementId).append("</Id><ElctrncSeqNb>").append(sequence).append("</ElctrncSeqNb>")
                .append("<CreDtTm>2026-10-16T18:00:00Z</CreDtTm>")
                .append("<Acct><Id><Othr><Id>").append(accountId).append("</Id></Othr></Id><Ccy>").append(currency).append("</Ccy></Acct>");
        balance(xml, "OPBD", opening);
        balance(xml, "CLBD", closing);
        entries.forEach(xml::append);
        return xml.append("</Stmt></BkToCstmrStmt></Document>").toString();
    }

    public byte[] bytes() {
        return xml().getBytes(StandardCharsets.UTF_8);
    }

    private void balance(StringBuilder xml, String type, BigDecimal amount) {
        xml.append("<Bal><Tp><CdOrPrtry><Cd>").append(type).append("</Cd></CdOrPrtry></Tp><Amt Ccy=\"").append(currency)
                .append("\">").append(amount.abs().toPlainString()).append("</Amt><CdtDbtInd>")
                .append(amount.signum() < 0 ? "DBIT" : "CRDT").append("</CdtDbtInd><Dt><Dt>").append(date).append("</Dt></Dt></Bal>");
    }
}
