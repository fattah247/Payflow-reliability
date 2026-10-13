package id.fatarc.portfolio.payflowreliability.ledger;

import id.fatarc.portfolio.payflowreliability.common.Money;
import id.fatarc.portfolio.payflowreliability.ledger.dto.BalanceView;
import id.fatarc.portfolio.payflowreliability.ledger.dto.JournalView;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
public class LedgerQueryService {
    private final LedgerJournalRepository journalRepository;
    private final LedgerPostingRepository postingRepository;

    public LedgerQueryService(LedgerJournalRepository journalRepository, LedgerPostingRepository postingRepository) {
        this.journalRepository = journalRepository;
        this.postingRepository = postingRepository;
    }

    @Transactional(readOnly = true)
    public List<BalanceView> balances(String merchantId, String currency) {
        return postingRepository.balances().stream()
                .filter(row -> merchantId == null || row.getAccount().equals(LedgerAccounts.merchantPayable(merchantId)))
                .filter(row -> currency == null || row.getCurrency().equals(currency))
                .map(row -> {
                    boolean merchantAccount = LedgerAccounts.isMerchantPayable(row.getAccount());
                    var balance = Money.normalize(row.getBalance(), row.getCurrency());
                    return new BalanceView(row.getAccount(),
                            merchantAccount ? LedgerAccounts.merchantOf(row.getAccount()) : null,
                            row.getCurrency(), balance, merchantAccount ? balance.negate() : null);
                })
                .toList();
    }

    @Transactional(readOnly = true)
    public List<JournalView> journalsForPayment(UUID paymentIntentId) {
        List<LedgerJournal> journals = journalRepository.findByPaymentIntentIdOrderByCreatedAtAsc(paymentIntentId);
        Map<UUID, List<LedgerPosting>> postings = postingRepository
                .findByJournalIdIn(journals.stream().map(LedgerJournal::getId).toList()).stream()
                .collect(Collectors.groupingBy(LedgerPosting::getJournalId));
        return journals.stream()
                .map(journal -> new JournalView(journal.getId(), journal.getJournalType(), journal.getReference(),
                        journal.getPaymentIntentId(), journal.getCurrency(), journal.getDescription(),
                        journal.getRequestId(), journal.getCreatedAt(),
                        postings.getOrDefault(journal.getId(), List.of()).stream()
                                .map(posting -> new JournalView.PostingView(posting.getAccount(), posting.getAmount()))
                                .toList()))
                .toList();
    }
}
