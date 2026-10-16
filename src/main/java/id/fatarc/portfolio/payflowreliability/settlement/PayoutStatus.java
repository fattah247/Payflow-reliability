package id.fatarc.portfolio.payflowreliability.settlement;

/**
 * Where a batch's money is. Only a bank credit matched by reference, amount and value-date window makes a
 * batch LANDED: the provider saying it paid is evidence of IN_TRANSIT, not of arrival.
 *
 * <pre>
 *   SENT ──provider accepts──▶ IN_TRANSIT ──matched bank credit──▶ LANDED ──bank returns it──▶ BROKEN(RETURNED)
 *     │                            │
 *     └─provider refuses─▶ BROKEN  └─no credit by the end of the window─▶ BROKEN(MISSING_CREDIT) ──late credit──▶ LANDED
 * </pre>
 */
public enum PayoutStatus {
    /** Paid out before the bank leg existed; there is no bank evidence to match. */
    LEGACY,
    /** The batch exists and the payout instruction is queued for the provider. */
    SENT,
    /** The provider accepted the payout and reported its fee, net amount and expected value date. */
    IN_TRANSIT,
    /** A bank credit matched the batch; the money is in the settlement account. */
    LANDED,
    /** Something went wrong; see the broken reason and the open break. */
    BROKEN
}
