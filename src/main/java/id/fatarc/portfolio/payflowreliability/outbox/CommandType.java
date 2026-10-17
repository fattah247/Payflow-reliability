package id.fatarc.portfolio.payflowreliability.outbox;

public enum CommandType {
    REFUND,
    REVERSAL,
    /** Pay a settlement batch out to the settlement account. About a batch, not a payment. */
    PAYOUT
}
