package id.fatarc.portfolio.payflowreliability.ledger;

import id.fatarc.portfolio.payflowreliability.common.Money;
import id.fatarc.portfolio.payflowreliability.common.RequestId;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Posts balanced journals in the caller's transaction, so a money movement and its state change
 * commit together or not at all. Posting is idempotent per (type, reference).
 */
@Service
public class LedgerService {
    private final LedgerJournalRepository journalRepository;
    private final LedgerPostingRepository postingRepository;
    private final Clock clock;

    public LedgerService(LedgerJournalRepository journalRepository, LedgerPostingRepository postingRepository, Clock clock) {
        this.journalRepository = journalRepository;
        this.postingRepository = postingRepository;
        this.clock = clock;
    }

    public record Posting(String account, BigDecimal amount) {
    }

    /** The customer's money is now with the provider and owed to the merchant. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void capture(UUID paymentIntentId, String merchantId, String currency, BigDecimal amount) {
        post(JournalType.CAPTURE, paymentIntentId.toString(), paymentIntentId, currency,
                "Payment captured by the provider.",
                List.of(new Posting(LedgerAccounts.PROVIDER_RECEIVABLE, amount),
                        new Posting(LedgerAccounts.merchantPayable(merchantId), amount.negate())));
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void refund(UUID refundId, UUID paymentIntentId, String merchantId, String currency, BigDecimal amount) {
        post(JournalType.REFUND, refundId.toString(), paymentIntentId, currency,
                "Refund confirmed by the provider.",
                List.of(new Posting(LedgerAccounts.merchantPayable(merchantId), amount),
                        new Posting(LedgerAccounts.PROVIDER_RECEIVABLE, amount.negate())));
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void reversal(UUID paymentIntentId, String merchantId, String currency, BigDecimal amount) {
        if (amount.signum() == 0) {
            return;
        }
        post(JournalType.REVERSAL, paymentIntentId.toString(), paymentIntentId, currency,
                "Reversal confirmed by the provider.",
                List.of(new Posting(LedgerAccounts.merchantPayable(merchantId), amount),
                        new Posting(LedgerAccounts.PROVIDER_RECEIVABLE, amount.negate())));
    }

    /**
     * A settlement batch commits the merchant's balance to a payout. Nothing has moved yet: the provider still
     * holds the money (provider_receivable) until it accepts the payout, and only a matched bank credit puts
     * it in our cash.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void payout(UUID batchId, String merchantId, String currency, BigDecimal amount) {
        post(JournalType.PAYOUT, batchId.toString(), null, currency,
                "Settlement batch committed to a payout to the merchant.",
                List.of(new Posting(LedgerAccounts.merchantPayable(merchantId), amount),
                        new Posting(LedgerAccounts.payoutsDue(merchantId), amount.negate())));
    }

    /** The provider accepted the payout: the gross amount left its balance and is on its way to the bank. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void payoutInTransit(UUID batchId, String currency, BigDecimal gross) {
        post(JournalType.PAYOUT_IN_TRANSIT, batchId.toString(), null, currency,
                "Provider accepted the payout; in transit to the settlement account.",
                List.of(new Posting(LedgerAccounts.PAYOUTS_IN_TRANSIT, gross),
                        new Posting(LedgerAccounts.PROVIDER_RECEIVABLE, gross.negate())));
    }

    /**
     * A bank credit matched the batch. Gross leaves transit and splits into what arrived, the provider's fee
     * and any bank charges, each on its own line, so gross minus fees equals net to the cent or the journal
     * does not balance and is refused.
     *
     * @param fromSuspense the credit was booked to suspense when it arrived (no match then), so it moves out of
     *                     suspense instead of being counted as cash a second time
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void payoutLanded(UUID batchId, String currency, BigDecimal gross, BigDecimal received, BigDecimal providerFee,
                             BigDecimal bankCharges, boolean fromSuspense) {
        List<Posting> postings = new java.util.ArrayList<>();
        postings.add(new Posting(fromSuspense ? LedgerAccounts.BANK_SUSPENSE : LedgerAccounts.BANK_CASH, received));
        if (providerFee.signum() != 0) {
            postings.add(new Posting(LedgerAccounts.PROVIDER_FEES, providerFee));
        }
        if (bankCharges.signum() != 0) {
            postings.add(new Posting(LedgerAccounts.BANK_CHARGES, bankCharges));
        }
        postings.add(new Posting(LedgerAccounts.PAYOUTS_IN_TRANSIT, gross.negate()));
        post(JournalType.PAYOUT_LANDED, batchId.toString(), null, currency,
                "Bank credit matched the payout: net to cash, fees as their own lines.", postings);
    }

    /** A bank line nobody could match: in the bank, unexplained. Credits add to suspense, debits take from it. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void bankUnmatched(UUID bankLineId, String currency, BigDecimal signedAmount) {
        post(JournalType.BANK_UNMATCHED, bankLineId.toString(), null, currency,
                "Bank line held in suspense until it is explained.",
                List.of(new Posting(LedgerAccounts.BANK_CASH, signedAmount),
                        new Posting(LedgerAccounts.BANK_SUSPENSE, signedAmount.negate())));
    }

    /** The bank returned a landed payout to the provider: cash goes back to being the provider's debt to us. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void payoutReturned(UUID bankLineId, String currency, BigDecimal amount, boolean fromSuspense) {
        post(JournalType.PAYOUT_RETURNED, bankLineId.toString(), null, currency,
                "Bank returned the payout to the provider.",
                List.of(new Posting(LedgerAccounts.PROVIDER_RECEIVABLE, amount),
                        new Posting(fromSuspense ? LedgerAccounts.BANK_SUSPENSE : LedgerAccounts.BANK_CASH, amount.negate())));
    }

    /**
     * @return false when this (type, reference) was already posted, so retries never double count
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean post(JournalType type, String reference, UUID paymentIntentId, String currency,
                        String description, List<Posting> postings) {
        if (postings.size() < 2) {
            throw new IllegalArgumentException("A journal needs at least two postings.");
        }
        BigDecimal sum = postings.stream().map(Posting::amount).reduce(BigDecimal.ZERO, BigDecimal::add);
        if (sum.signum() != 0) {
            throw new IllegalArgumentException("Journal " + type + " " + reference + " does not balance: " + sum);
        }
        if (postings.stream().anyMatch(posting -> posting.amount().signum() == 0)) {
            throw new IllegalArgumentException("Journal " + type + " " + reference + " has a zero posting.");
        }
        if (journalRepository.existsByJournalTypeAndReference(type, reference)) {
            return false;
        }
        Instant now = clock.instant();
        LedgerJournal journal = journalRepository.save(
                new LedgerJournal(type, reference, paymentIntentId, currency, description, RequestId.current(), now));
        for (Posting posting : postings) {
            postingRepository.save(new LedgerPosting(journal.getId(), posting.account(), currency,
                    Money.normalize(posting.amount(), currency), now));
        }
        return true;
    }
}
