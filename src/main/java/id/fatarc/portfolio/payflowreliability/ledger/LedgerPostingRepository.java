package id.fatarc.portfolio.payflowreliability.ledger;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.math.BigDecimal;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

public interface LedgerPostingRepository extends JpaRepository<LedgerPosting, UUID> {
    List<LedgerPosting> findByJournalIdIn(Collection<UUID> journalIds);

    @Query("select p.account as account, p.currency as currency, sum(p.amount) as balance from LedgerPosting p group by p.account, p.currency order by p.account, p.currency")
    List<AccountBalance> balances();

    @Query("select p.journalId as journalId, sum(p.amount) as imbalance from LedgerPosting p group by p.journalId having sum(p.amount) <> 0")
    List<JournalImbalance> unbalancedJournals();

    interface AccountBalance {
        String getAccount();
        String getCurrency();
        BigDecimal getBalance();
    }

    interface JournalImbalance {
        UUID getJournalId();
        BigDecimal getImbalance();
    }
}
