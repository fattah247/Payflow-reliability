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

    @Transactional(propagation = Propagation.MANDATORY)
    public void payout(UUID batchId, String merchantId, String currency, BigDecimal amount) {
        post(JournalType.PAYOUT, batchId.toString(), null, currency,
                "Settlement batch paid out to the merchant.",
                List.of(new Posting(LedgerAccounts.merchantPayable(merchantId), amount),
                        new Posting(LedgerAccounts.PROVIDER_RECEIVABLE, amount.negate())));
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
