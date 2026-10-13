package id.fatarc.portfolio.payflowreliability.review;

import java.util.EnumSet;
import java.util.Set;

public enum ReviewStatus {
    OPEN,
    /** A money-moving decision was proposed and waits for a second operator. */
    AWAITING_APPROVAL,
    RESOLVED;

    public static final Set<ReviewStatus> UNRESOLVED = EnumSet.of(OPEN, AWAITING_APPROVAL);
}
