package id.fatarc.portfolio.payflowreliability.payment;

/** What happened to one provider webhook delivery. */
public enum WebhookOutcome {
    /** The event changed the payment state. HTTP 200. */
    APPLIED,
    /** The event (or the fact it states) was already processed; nothing changed. HTTP 200. */
    DUPLICATE_IGNORED,
    /** An authorization arrived after the payment was already captured; capture implies authorization. HTTP 200. */
    STALE_IGNORED,
    /** The event was recorded but needs a human: a review case was opened. HTTP 202. */
    SENT_TO_REVIEW,
    /** The payment is on hold for review; the event is not recorded so the provider's redelivery applies it later. HTTP 409. */
    DEFERRED
}
