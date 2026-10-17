package id.fatarc.portfolio.payflowreliability.reconciliation.breaks;

public enum BreakResolution {
    /** The system explained it: the missing credit arrived, a suspense line matched once the provider confirmed. */
    MATCHED,
    /** An operator assigned an unmatched or late credit to the payout it belongs to (amount still checked). */
    ASSIGNED_TO_BATCH,
    /** An operator looked at a break that holds no money and closed it with a note. */
    ACKNOWLEDGED
}
