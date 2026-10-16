package id.fatarc.portfolio.payflowreliability.bank;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * One parsed end-of-day statement (camt.053). Balances are signed: credit positive, debit negative.
 *
 * @param statementId    Stmt/Id, unique per statement
 * @param sequenceNumber Stmt/ElctrncSeqNb, consecutive per account: a gap means a statement has not arrived
 */
public record CamtStatement(String statementId, long sequenceNumber, String accountId, String currency, LocalDate date,
                            BigDecimal openingBalance, BigDecimal closingBalance, List<Entry> entries) {

    /**
     * @param amount             what was booked, always positive
     * @param creditDebit        CRDT (money in) or DBIT (money out)
     * @param reversal           RvslInd: the entry reverses an earlier one (a returned payment)
     * @param accountServicerRef the bank's own unique reference for the entry
     * @param endToEndId         the reference the payer put on the transfer; null when absent or NOTPROVIDED
     * @param instructedAmount   what the payer sent, when the bank deducted charges on the way
     * @param chargesAmount      charges the bank deducted from this entry, itemized
     */
    public record Entry(BigDecimal amount, String creditDebit, boolean reversal, LocalDate bookingDate, LocalDate valueDate,
                        String accountServicerRef, String endToEndId, String remittanceInfo, BigDecimal instructedAmount,
                        BigDecimal chargesAmount) {
        public boolean isCredit() {
            return "CRDT".equals(creditDebit);
        }

        public BigDecimal signedAmount() {
            return isCredit() ? amount : amount.negate();
        }
    }
}
