package id.fatarc.portfolio.payflowreliability.ledger;

/**
 * Chart of accounts. Postings use debit-positive amounts: provider_receivable has a debit balance
 * (money the provider holds for us); merchant_payable:&lt;id&gt; has a credit balance (money owed to the merchant).
 */
public final class LedgerAccounts {
    public static final String PROVIDER_RECEIVABLE = "provider_receivable";
    private static final String MERCHANT_PAYABLE_PREFIX = "merchant_payable:";

    private LedgerAccounts() {
    }

    public static String merchantPayable(String merchantId) {
        return MERCHANT_PAYABLE_PREFIX + merchantId;
    }

    public static boolean isMerchantPayable(String account) {
        return account.startsWith(MERCHANT_PAYABLE_PREFIX);
    }

    public static String merchantOf(String account) {
        return account.substring(MERCHANT_PAYABLE_PREFIX.length());
    }
}
