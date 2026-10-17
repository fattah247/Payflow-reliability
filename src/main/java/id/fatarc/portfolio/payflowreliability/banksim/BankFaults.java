package id.fatarc.portfolio.payflowreliability.banksim;

import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Optional;

/**
 * What can go wrong between a provider's payout and our statement. Faults are queued and each incoming credit
 * (or each issued statement, for RESEND_STATEMENT) takes the next one. In memory: a test and demo control.
 */
@Component
public class BankFaults {
    public enum Mode {
        /** The credit arrives {@code days} after the expected value date (default 3, beyond the matching window). */
        LATE_CREDIT,
        /** The credit never arrives. */
        DROP_CREDIT,
        /** The same transfer is credited twice, as two bank entries. */
        DUPLICATE_CREDIT,
        /** The bank deducts a charge of {@code amount} from the credit and itemizes it in the statement (camt Chrgs). */
        NET_OF_FEES,
        /** The credit carries a reference that matches no payout. */
        WRONG_REFERENCE,
        /** The credit carries no end-to-end reference at all. */
        MISSING_REFERENCE,
        /** The credit lands, then the bank returns it {@code days} later (a reversal debit, default 1). */
        RETURN_PAYOUT,
        /** The next statement is delivered twice by the statement feed (same id, same content). */
        RESEND_STATEMENT
    }

    /**
     * @param days   for LATE_CREDIT and RETURN_PAYOUT
     * @param amount for NET_OF_FEES: the charge deducted
     */
    public record Fault(Mode mode, Integer days, BigDecimal amount) {
    }

    private final Deque<Fault> creditFaults = new ArrayDeque<>();
    private final Deque<Fault> statementFaults = new ArrayDeque<>();

    public synchronized void inject(Mode mode, int times, Integer days, BigDecimal amount) {
        for (int i = 0; i < times; i++) {
            (mode == Mode.RESEND_STATEMENT ? statementFaults : creditFaults).addLast(new Fault(mode, days, amount));
        }
    }

    public synchronized void clear() {
        creditFaults.clear();
        statementFaults.clear();
    }

    synchronized Optional<Fault> nextCreditFault() {
        return Optional.ofNullable(creditFaults.pollFirst());
    }

    synchronized boolean nextStatementResent() {
        return statementFaults.pollFirst() != null;
    }
}
