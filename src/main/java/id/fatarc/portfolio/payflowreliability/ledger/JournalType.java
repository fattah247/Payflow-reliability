package id.fatarc.portfolio.payflowreliability.ledger;

/**
 * Money movements, each recorded once as a balanced journal.
 * <ul>
 *   <li>CAPTURE: the provider holds the customer's money; we owe it to the merchant.</li>
 *   <li>REFUND / REVERSAL: money goes back to the customer; the merchant bears it.</li>
 *   <li>PAYOUT: the provider pays the merchant; our debt to the merchant is settled.</li>
 * </ul>
 */
public enum JournalType {
    CAPTURE,
    REFUND,
    REVERSAL,
    PAYOUT
}
