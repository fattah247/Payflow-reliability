package id.fatarc.portfolio.payflowreliability.bank;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface BankStatementLineRepository extends JpaRepository<BankStatementLine, UUID>, JpaSpecificationExecutor<BankStatementLine> {
    boolean existsByLineHash(String lineHash);

    List<BankStatementLine> findByStatementIdOrderByValueDateAscAccountServicerRefAsc(String statementId);

    List<BankStatementLine> findBySettlementBatchIdOrderByImportedAtAsc(UUID settlementBatchId);

    List<BankStatementLine> findByMatchStatusOrderByImportedAtAsc(BankLineStatus status);

    Optional<BankStatementLine> findFirstByEndToEndIdAndCreditDebitAndMatchStatusOrderByImportedAtAsc(String endToEndId,
                                                                                                       String creditDebit,
                                                                                                       BankLineStatus status);

    /** Net effect of every imported line on each currency's bank balance (credits minus debits). */
    @Query("""
            select l.currency as currency,
                   sum(case when l.creditDebit = 'CRDT' then l.amount else -l.amount end) as amount
            from BankStatementLine l group by l.currency""")
    List<CurrencyTotal> netByCurrency();

    interface CurrencyTotal {
        String getCurrency();
        BigDecimal getAmount();
    }
}
