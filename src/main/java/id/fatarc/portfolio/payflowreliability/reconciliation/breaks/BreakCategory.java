package id.fatarc.portfolio.payflowreliability.reconciliation.breaks;

import java.time.Duration;

/**
 * Every way the ledger, the provider and the bank can disagree about a payout, with who owns it and how long
 * it may stay open before it is overdue (and pages). Breaks that hold money in {@code bank_suspense} can only
 * be closed by explaining that money; the others can also be acknowledged with a note.
 */
public enum BreakCategory {
    /** Every statement through the end of the value-date window arrived; none had the payout's credit. */
    MISSING_CREDIT(BreakOwner.TREASURY, Duration.ofHours(24), false),
    /** The provider refused the payout instruction; the money is still in the provider balance. */
    PROVIDER_REFUSED_PAYOUT(BreakOwner.PROVIDER_OPERATIONS, Duration.ofHours(24), false),
    /** The bank sent a landed payout back to the provider. */
    RETURNED_PAYOUT(BreakOwner.TREASURY, Duration.ofHours(24), false),
    /** A credit whose reference matches no payout (wrong or missing reference). Held in suspense. */
    UNMATCHED_CREDIT(BreakOwner.TREASURY, Duration.ofHours(48), true),
    /** A debit nobody expected (for example a return of something not yet matched). Held in suspense. */
    UNMATCHED_DEBIT(BreakOwner.TREASURY, Duration.ofHours(48), true),
    /** A second credit for a payout that already landed. Held in suspense until returned or explained. */
    DUPLICATE_CREDIT(BreakOwner.TREASURY, Duration.ofHours(72), true),
    /** The reference matches but the amount does not (after the provider's fee and itemized bank charges). */
    AMOUNT_MISMATCH(BreakOwner.PROVIDER_OPERATIONS, Duration.ofHours(48), true),
    /** The right credit, outside the value-date window. Held in suspense until someone accepts it. */
    LATE_CREDIT(BreakOwner.TREASURY, Duration.ofHours(48), true),
    /** The provider's settlement report disagrees with the ledger or with what it said when it accepted the payout. */
    PROVIDER_PAYOUT_MISMATCH(BreakOwner.PROVIDER_OPERATIONS, Duration.ofHours(72), false),
    /** A payout we sent is absent from the provider's report for its date. */
    MISSING_FROM_PROVIDER_REPORT(BreakOwner.PROVIDER_OPERATIONS, Duration.ofHours(72), false),
    /** The provider's report lists a payout reference we never sent. */
    UNKNOWN_PROVIDER_PAYOUT(BreakOwner.PROVIDER_OPERATIONS, Duration.ofHours(72), false),
    /** A statement arrived with an id or sequence number we already hold, but different content. */
    STATEMENT_CONFLICT(BreakOwner.TREASURY, Duration.ofHours(24), false);

    private final BreakOwner owner;
    private final Duration dueAfter;
    private final boolean holdsSuspense;

    BreakCategory(BreakOwner owner, Duration dueAfter, boolean holdsSuspense) {
        this.owner = owner;
        this.dueAfter = dueAfter;
        this.holdsSuspense = holdsSuspense;
    }

    public BreakOwner owner() { return owner; }
    public Duration dueAfter() { return dueAfter; }
    /** Whether a break of this kind keeps an amount in bank_suspense while it is open. */
    public boolean holdsSuspense() { return holdsSuspense; }
}
