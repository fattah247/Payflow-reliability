package id.fatarc.portfolio.payflowreliability.ledger;

/**
 * Money movements, each recorded once as a balanced journal.
 * <ul>
 *   <li>CAPTURE: the provider holds the customer's money; we owe it to the merchant.</li>
 *   <li>REFUND / REVERSAL: money goes back to the customer; the merchant bears it.</li>
 *   <li>PAYOUT: a settlement batch commits the merchant's balance to a payout (batches from before the bank
 *       leg instead treated this as the provider paying the merchant directly).</li>
 *   <li>PAYOUT_IN_TRANSIT: the provider accepted the payout; the money left its balance but is not in the bank.</li>
 *   <li>PAYOUT_LANDED: a bank credit matched the batch; net in cash, provider fee and bank charges as expenses.</li>
 *   <li>PAYOUT_RETURNED: the bank sent a landed payout back; the provider holds the money again.</li>
 *   <li>BANK_UNMATCHED: a bank line nobody could explain yet; held in suspense, with an open break.</li>
 * </ul>
 */
public enum JournalType {
    CAPTURE,
    REFUND,
    REVERSAL,
    PAYOUT,
    PAYOUT_IN_TRANSIT,
    PAYOUT_LANDED,
    PAYOUT_RETURNED,
    BANK_UNMATCHED
}
