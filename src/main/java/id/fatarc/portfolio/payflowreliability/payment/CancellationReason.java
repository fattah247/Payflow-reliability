package id.fatarc.portfolio.payflowreliability.payment;

/** Why a payment was withdrawn before the customer paid. */
public enum CancellationReason {
    /** The customer left the checkout (for example after waiting too long for the payment page). */
    ABANDONED,
    /** The customer asked to cancel. */
    CUSTOMER_REQUEST,
    /** The merchant withdrew the payment (an order cancelled for another reason). */
    MERCHANT_REQUEST
}
