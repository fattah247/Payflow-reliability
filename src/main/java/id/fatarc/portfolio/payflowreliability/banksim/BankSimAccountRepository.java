package id.fatarc.portfolio.payflowreliability.banksim;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface BankSimAccountRepository extends JpaRepository<BankSimAccount, String> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select a from BankSimAccount a where a.accountId = :id")
    Optional<BankSimAccount> findForUpdate(@Param("id") String accountId);
}
