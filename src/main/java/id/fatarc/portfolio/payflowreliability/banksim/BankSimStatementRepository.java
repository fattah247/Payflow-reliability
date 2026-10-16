package id.fatarc.portfolio.payflowreliability.banksim;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface BankSimStatementRepository extends JpaRepository<BankSimStatement, String> {
    List<BankSimStatement> findByAccountIdAndSequenceNumberGreaterThanOrderBySequenceNumberAsc(String accountId, long afterSequence);
}
