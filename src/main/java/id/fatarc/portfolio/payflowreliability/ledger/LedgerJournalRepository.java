package id.fatarc.portfolio.payflowreliability.ledger;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface LedgerJournalRepository extends JpaRepository<LedgerJournal, UUID> {
    boolean existsByJournalTypeAndReference(JournalType journalType, String reference);

    List<LedgerJournal> findByPaymentIntentIdOrderByCreatedAtAsc(UUID paymentIntentId);

    List<LedgerJournal> findByReferenceOrderByCreatedAtAsc(String reference);
}
