package id.fatarc.portfolio.payflowreliability.refund;

/**
 * @param replayed true when an equivalent refund for the payment already existed and was returned instead
 */
public record RefundSubmission(RefundRequest refund, boolean replayed) {
}
