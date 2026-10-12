package id.fatarc.portfolio.payflowreliability.audit;

/** Aggregate types used for audit events and review-case references. */
public final class AggregateType {
    public static final String PAYMENT_INTENT = "PAYMENT_INTENT";
    public static final String PROVIDER_EVENT = "PROVIDER_EVENT";
    public static final String PROVIDER_TRANSACTION = "PROVIDER_TRANSACTION";
    public static final String REFUND_REQUEST = "REFUND_REQUEST";
    public static final String SETTLEMENT_BATCH = "SETTLEMENT_BATCH";
    public static final String MANUAL_REVIEW = "MANUAL_REVIEW";

    private AggregateType() {
    }
}
