package id.fatarc.portfolio.payflowreliability.banksim;

import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public interface BankSimEntryRepository extends JpaRepository<BankSimEntry, UUID> {
    List<BankSimEntry> findByAccountIdAndStatementIdIsNullAndBookingDateLessThanEqualOrderByCreatedAtAsc(String accountId,
                                                                                                       LocalDate bookingDate);

    List<BankSimEntry> findByEndToEndIdOrderByCreatedAtAsc(String endToEndId);
}
