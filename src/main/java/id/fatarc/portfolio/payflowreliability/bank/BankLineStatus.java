package id.fatarc.portfolio.payflowreliability.bank;

/** What became of a bank line. Every line is booked exactly once, whatever its status. */
public enum BankLineStatus {
    /** Matched to a payout by reference, amount and value-date window; the payout landed. */
    MATCHED,
    /** Not explained (yet): booked to bank_suspense, with an open break. */
    SUSPENSE,
    /** A return of a landed payout: booked back to the provider receivable, with a break. */
    RETURN,
    /** Was in suspense, then explained (matched later, assigned by an operator, or netted by a return). */
    CLEARED
}
