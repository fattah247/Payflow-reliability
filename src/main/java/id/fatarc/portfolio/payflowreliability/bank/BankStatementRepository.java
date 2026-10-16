package id.fatarc.portfolio.payflowreliability.bank;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface BankStatementRepository extends JpaRepository<BankStatement, UUID> {
    Optional<BankStatement> findByStatementId(String statementId);

    Optional<BankStatement> findByAccountIdAndSequenceNumber(String accountId, long sequenceNumber);

    List<BankStatement> findByAccountIdOrderBySequenceNumberAsc(String accountId);

    List<BankStatement> findAllByOrderByImportedAtDesc();

    @Query("select coalesce(max(s.sequenceNumber), 0) from BankStatement s where s.accountId = :account")
    long maxSequence(@Param("account") String accountId);
}
