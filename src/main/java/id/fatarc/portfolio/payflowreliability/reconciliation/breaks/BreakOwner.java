package id.fatarc.portfolio.payflowreliability.reconciliation.breaks;

/** Who has to act on a break. Each owner talks to a different counterparty. */
public enum BreakOwner {
    /** Talks to the bank: missing, late, duplicated, returned or unexplained money in the settlement account. */
    TREASURY,
    /** Talks to the provider: refused payouts, amounts or reports that disagree with what the provider said. */
    PROVIDER_OPERATIONS
}
