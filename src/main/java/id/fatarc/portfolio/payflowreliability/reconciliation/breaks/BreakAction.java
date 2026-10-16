package id.fatarc.portfolio.payflowreliability.reconciliation.breaks;

import java.util.EnumSet;
import java.util.List;

/** What an operator can do with an open break. */
public enum BreakAction {
    /**
     * Say which payout an unmatched or late credit belongs to. Overrides the reference and the window, never the
     * amount: gross minus the provider fee and the bank charges must still equal what arrived.
     */
    ASSIGN_TO_BATCH,
    /** Close a break that holds no money, with a note (the investigation happened outside the system). */
    ACKNOWLEDGE;

    private static final EnumSet<BreakCategory> ASSIGNABLE = EnumSet.of(BreakCategory.UNMATCHED_CREDIT, BreakCategory.LATE_CREDIT);

    static List<BreakAction> allowedFor(ReconciliationBreak open) {
        if (!open.isOpen()) {
            return List.of();
        }
        if (ASSIGNABLE.contains(open.getCategory()) && open.getBankLineId() != null) {
            return List.of(ASSIGN_TO_BATCH);
        }
        return open.getCategory().holdsSuspense() ? List.of() : List.of(ACKNOWLEDGE);
    }
}
