package id.fatarc.portfolio.payflowreliability.settlement;

public enum BrokenReason {
    /** The provider refused the payout instruction; the money is still in the provider balance. */
    PROVIDER_REFUSED,
    /** Every statement through the end of the value-date window arrived, and none had the credit. */
    MISSING_CREDIT,
    /** The bank sent a landed payout back. */
    RETURNED
}
