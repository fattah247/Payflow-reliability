package id.fatarc.portfolio.payflowreliability.review;

import java.util.EnumSet;
import java.util.Set;

/** The decision an operator records when resolving a review case. */
public enum ReviewAction {
    /** Close the case without changing any payment or refund. */
    ACKNOWLEDGE,
    /** Return a held payment to the state it was held from. */
    RELEASE_PAYMENT,
    /** Fail a held payment that was never captured. */
    FAIL_PAYMENT,
    /** Reverse a held payment that was authorized or captured (two-person approval). */
    REQUIRE_REVERSAL,
    /** Let a refund on a reconciled payment proceed (two-person approval). */
    APPROVE_REFUND,
    /** Decline a refund on a reconciled payment. */
    REJECT_REFUND,
    /** Send a provider call again whose outcome is unknown (safe: the provider deduplicates by key). */
    RETRY_PROVIDER_CALL;

    /** Decisions that move money take effect only when a second operator approves them. */
    public static final Set<ReviewAction> DUAL_CONTROL = EnumSet.of(REQUIRE_REVERSAL, APPROVE_REFUND);

    public boolean requiresApproval() {
        return DUAL_CONTROL.contains(this);
    }
}
