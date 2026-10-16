package id.fatarc.portfolio.payflowreliability.bank;

import id.fatarc.portfolio.payflowreliability.common.BusinessException;
import id.fatarc.portfolio.payflowreliability.common.ErrorCode;
import id.fatarc.portfolio.payflowreliability.common.Money;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * Reads the camt.053 subset this platform relies on (documented in docs/settlement-certainty.md) and refuses
 * anything it cannot trust:
 * <ul>
 *   <li>the parser never resolves external entities or DTDs (statement files come from outside);</li>
 *   <li>exactly one statement per file, with an id, a sequence number, an account and a currency;</li>
 *   <li>amounts in the account currency, with no more decimals than the currency allows;</li>
 *   <li>opening balance plus entries equals closing balance, to the cent.</li>
 * </ul>
 */
public final class CamtStatementParser {
    public static final String NAMESPACE = "urn:iso:std:iso:20022:tech:xsd:camt.053.001.08";

    private CamtStatementParser() {
    }

    public static CamtStatement parse(byte[] xml) {
        try {
            return parseDocument(read(xml));
        } catch (BusinessException ex) {
            throw ex;
        } catch (RuntimeException ex) {
            throw invalid(ex.getClass().getSimpleName() + ": " + ex.getMessage());
        }
    }

    private static CamtStatement parseDocument(Document document) {
        Element root = document.getDocumentElement();
        if (!"Document".equals(root.getLocalName()) || !NAMESPACE.equals(root.getNamespaceURI())) {
            throw invalid("not a camt.053.001.08 document (namespace " + root.getNamespaceURI() + ")");
        }
        List<Element> statements = children(only(root, "BkToCstmrStmt"), "Stmt");
        if (statements.size() != 1) {
            throw invalid("expected exactly one Stmt, found " + statements.size());
        }
        Element statement = statements.getFirst();
        String statementId = text(statement, "Id");
        long sequence = Long.parseLong(text(statement, "ElctrncSeqNb"));
        Element account = only(statement, "Acct");
        String accountId = text(only(only(only(account, "Id"), "Othr"), "Id"));
        String currency = text(account, "Ccy");
        if (!Money.isSupportedCurrency(currency)) {
            throw invalid("unsupported account currency " + currency);
        }

        BigDecimal opening = null;
        BigDecimal closing = null;
        LocalDate date = null;
        for (Element balance : children(statement, "Bal")) {
            String type = text(only(only(balance, "Tp"), "CdOrPrtry"), "Cd");
            BigDecimal amount = signed(amount(balance, currency), text(balance, "CdtDbtInd"));
            LocalDate balanceDate = LocalDate.parse(text(only(balance, "Dt"), "Dt"));
            if ("OPBD".equals(type)) {
                opening = amount;
            } else if ("CLBD".equals(type)) {
                closing = amount;
                date = balanceDate;
            }
        }
        if (opening == null || closing == null) {
            throw invalid("statement " + statementId + " needs an opening (OPBD) and a closing (CLBD) booked balance");
        }

        List<CamtStatement.Entry> entries = new ArrayList<>();
        BigDecimal movement = BigDecimal.ZERO;
        for (Element entry : children(statement, "Ntry")) {
            CamtStatement.Entry parsed = entry(entry, currency);
            movement = movement.add(parsed.signedAmount());
            entries.add(parsed);
        }
        if (opening.add(movement).compareTo(closing) != 0) {
            throw invalid("statement " + statementId + " does not add up: opening " + opening.toPlainString() + " + entries "
                    + movement.toPlainString() + " != closing " + closing.toPlainString());
        }
        return new CamtStatement(statementId, sequence, accountId, currency, date, opening, closing, List.copyOf(entries));
    }

    private static CamtStatement.Entry entry(Element entry, String currency) {
        String direction = text(entry, "CdtDbtInd");
        if (!"CRDT".equals(direction) && !"DBIT".equals(direction)) {
            throw invalid("entry direction must be CRDT or DBIT, was " + direction);
        }
        String status = text(only(entry, "Sts"), "Cd");
        if (!"BOOK".equals(status)) {
            throw invalid("only booked entries are accepted in an end-of-day statement, found " + status);
        }
        BigDecimal amount = amount(entry, currency);
        if (amount.signum() <= 0) {
            throw invalid("entry amounts must be positive");
        }
        Element details = optional(optional(entry, "NtryDtls"), "TxDtls");
        Element refs = optional(details, "Refs");
        String endToEnd = optionalText(refs, "EndToEndId");
        if (endToEnd != null && (endToEnd.isBlank() || "NOTPROVIDED".equals(endToEnd))) {
            endToEnd = null;
        }
        Element instructed = optional(optional(details, "AmtDtls"), "InstdAmt");
        Element charges = optional(entry, "Chrgs");
        return new CamtStatement.Entry(
                amount,
                direction,
                Boolean.parseBoolean(optionalText(entry, "RvslInd")),
                LocalDate.parse(text(only(entry, "BookgDt"), "Dt")),
                LocalDate.parse(text(only(entry, "ValDt"), "Dt")),
                text(entry, "AcctSvcrRef"),
                endToEnd,
                optionalText(optional(details, "RmtInf"), "Ustrd"),
                instructed == null ? null : amount(instructed, currency),
                charges == null ? null : amount(only(charges, "Rcrd"), currency));
    }

    private static Document read(byte[] xml) {
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(true);
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
            factory.setXIncludeAware(false);
            factory.setExpandEntityReferences(false);
            DocumentBuilder builder = factory.newDocumentBuilder();
            builder.setErrorHandler(null);
            return builder.parse(new ByteArrayInputStream(xml));
        } catch (Exception ex) {
            throw invalid("not well-formed XML: " + ex.getMessage());
        }
    }

    private static BigDecimal amount(Element parent, String currency) {
        Element amount = only(parent, "Amt");
        if (!currency.equals(amount.getAttribute("Ccy"))) {
            throw invalid("amount in " + amount.getAttribute("Ccy") + " on a " + currency + " account");
        }
        BigDecimal value = new BigDecimal(amount.getTextContent().trim());
        if (Math.max(value.stripTrailingZeros().scale(), 0) > Money.fractionDigits(currency)) {
            throw invalid("amount " + value.toPlainString() + " has more decimals than " + currency + " allows");
        }
        return Money.normalize(value, currency);
    }

    private static BigDecimal signed(BigDecimal amount, String direction) {
        return "DBIT".equals(direction) ? amount.negate() : amount;
    }

    private static List<Element> children(Element parent, String name) {
        List<Element> found = new ArrayList<>();
        NodeList nodes = parent.getChildNodes();
        for (int i = 0; i < nodes.getLength(); i++) {
            Node node = nodes.item(i);
            if (node instanceof Element element && name.equals(element.getLocalName())) {
                found.add(element);
            }
        }
        return found;
    }

    private static Element only(Element parent, String name) {
        Element found = optional(parent, name);
        if (found == null) {
            throw invalid(parent.getLocalName() + " has no " + name);
        }
        return found;
    }

    private static Element optional(Element parent, String name) {
        if (parent == null) {
            return null;
        }
        List<Element> found = children(parent, name);
        if (found.size() > 1) {
            throw invalid(parent.getLocalName() + " has more than one " + name);
        }
        return found.isEmpty() ? null : found.getFirst();
    }

    private static String text(Element parent, String name) {
        return text(only(parent, name));
    }

    private static String text(Element element) {
        String value = element.getTextContent().trim();
        if (value.isEmpty()) {
            throw invalid(element.getLocalName() + " is empty");
        }
        return value;
    }

    private static String optionalText(Element parent, String name) {
        Element element = optional(parent, name);
        return element == null ? null : element.getTextContent().trim();
    }

    private static BusinessException invalid(String reason) {
        return new BusinessException(ErrorCode.BANK_STATEMENT_INVALID, "Bank statement rejected: " + reason);
    }
}
