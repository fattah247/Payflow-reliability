package id.fatarc.portfolio.payflowreliability.bank;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface BankAccountCursorRepository extends JpaRepository<BankAccountCursor, String> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select a from BankAccountCursor a where a.accountId = :id")
    Optional<BankAccountCursor> findForUpdate(@Param("id") String accountId);

    List<BankAccountCursor> findByCurrency(String currency);
}
