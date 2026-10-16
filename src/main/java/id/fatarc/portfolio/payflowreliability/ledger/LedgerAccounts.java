package id.fatarc.portfolio.payflowreliability.ledger;

/**
 * Chart of accounts. Postings use debit-positive amounts. Every account is kept per currency.
 *
 * <table>
 *   <caption>Accounts</caption>
 *   <tr><th>Account</th><th>Kind</th><th>Holds</th></tr>
 *   <tr><td>provider_receivable</td><td>asset</td><td>money the provider holds for us</td></tr>
 *   <tr><td>merchant_payable:&lt;id&gt;</td><td>liability</td><td>owed to the merchant, not yet in a payout batch</td></tr>
 *   <tr><td>payouts_due:&lt;id&gt;</td><td>liability</td><td>committed to a payout batch; transferred to the merchant
 *       from the settlement account (that transfer is outside this project)</td></tr>
 *   <tr><td>payouts_in_transit</td><td>asset</td><td>the provider says it sent it; not in the bank yet</td></tr>
 *   <tr><td>bank_cash</td><td>asset</td><td>in the settlement bank account, per its statements</td></tr>
 *   <tr><td>bank_suspense</td><td>liability</td><td>arrived in the bank, not yet explained (each amount has an open break)</td></tr>
 *   <tr><td>provider_fees</td><td>expense</td><td>fees the provider deducted from payouts</td></tr>
 *   <tr><td>bank_charges</td><td>expense</td><td>charges the bank deducted from incoming credits</td></tr>
 * </table>
 */
public final class LedgerAccounts {
    public static final String PROVIDER_RECEIVABLE = "provider_receivable";
    public static final String PAYOUTS_IN_TRANSIT = "payouts_in_transit";
    public static final String BANK_CASH = "bank_cash";
    public static final String BANK_SUSPENSE = "bank_suspense";
    public static final String PROVIDER_FEES = "provider_fees";
    public static final String BANK_CHARGES = "bank_charges";
    private static final String MERCHANT_PAYABLE_PREFIX = "merchant_payable:";
    private static final String PAYOUTS_DUE_PREFIX = "payouts_due:";

    private LedgerAccounts() {
    }

    public static String merchantPayable(String merchantId) {
        return MERCHANT_PAYABLE_PREFIX + merchantId;
    }

    public static String payoutsDue(String merchantId) {
        return PAYOUTS_DUE_PREFIX + merchantId;
    }

    public static boolean isMerchantPayable(String account) {
        return account.startsWith(MERCHANT_PAYABLE_PREFIX);
    }

    public static boolean isPayoutsDue(String account) {
        return account.startsWith(PAYOUTS_DUE_PREFIX);
    }

    public static String merchantOf(String account) {
        return account.substring(account.indexOf(':') + 1);
    }
}
