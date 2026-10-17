package id.fatarc.portfolio.payflowreliability.bank;

import java.time.LocalDate;
import java.util.List;

/**
 * What one statement import did.
 *
 * @param outcome            IMPORTED, ALREADY_IMPORTED (same id, same content: nothing done) or CONFLICT (same id or
 *                           sequence number, different content: nothing imported, a break opened)
 * @param newLines           lines booked for the first time
 * @param alreadyKnownLines  lines skipped because the same entry was already imported (for example in a re-issued statement)
 * @param landed             payouts this statement landed, by reference
 * @param suspense           lines booked to suspense, each with an open break
 * @param returns            returned payouts
 * @param coveredThrough     every statement of the account through this date has now arrived
 */
public record StatementImportResult(String statementId, String accountId, long sequenceNumber, LocalDate statementDate,
                                    Outcome outcome, int newLines, int alreadyKnownLines, List<String> landed,
                                    int suspense, int returns, int cleared, long coveredSequence,
                                    LocalDate coveredThrough, String detail) {
    public enum Outcome { IMPORTED, ALREADY_IMPORTED, CONFLICT }
}
