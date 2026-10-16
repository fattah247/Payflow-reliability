package id.fatarc.portfolio.payflowreliability.outbox;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface OutboxCommandRepository extends JpaRepository<OutboxCommand, UUID> {
    @Query("""
            select c.id from OutboxCommand c
            where c.status = id.fatarc.portfolio.payflowreliability.outbox.CommandStatus.PENDING
              and c.nextAttemptAt <= :now
              and (c.leaseUntil is null or c.leaseUntil < :now)
            order by c.nextAttemptAt, c.id
            """)
    List<UUID> findDue(@Param("now") Instant now, Pageable page);

    /**
     * Claims a command for one worker until the lease expires. Exactly one concurrent caller gets 1;
     * a crashed worker's lease simply expires and another worker resends with the same idempotency key.
     */
    @Modifying
    @Query("""
            update OutboxCommand c set c.leaseOwner = :owner, c.leaseUntil = :until, c.version = c.version + 1
            where c.id = :id
              and c.status = id.fatarc.portfolio.payflowreliability.outbox.CommandStatus.PENDING
              and (c.leaseUntil is null or c.leaseUntil < :now)
            """)
    int claim(@Param("id") UUID id, @Param("owner") String owner, @Param("now") Instant now, @Param("until") Instant until);

    Optional<OutboxCommand> findByIdempotencyKey(String idempotencyKey);

    /** Reads only the payment id, so the command itself is loaded after the payment lock is taken. */
    @Query("select c.paymentIntentId from OutboxCommand c where c.id = :id")
    Optional<UUID> findPaymentIntentIdById(@Param("id") UUID id);

    /** What a command is about (a payment, or a settlement batch for payouts), read before taking any lock. */
    @Query("select c.commandType as commandType, c.paymentIntentId as paymentIntentId, c.settlementBatchId as settlementBatchId"
            + " from OutboxCommand c where c.id = :id")
    Optional<Subject> findSubjectById(@Param("id") UUID id);

    List<OutboxCommand> findBySettlementBatchIdOrderByCreatedAtAsc(UUID settlementBatchId);

    interface Subject {
        CommandType getCommandType();
        UUID getPaymentIntentId();
        UUID getSettlementBatchId();
    }

    List<OutboxCommand> findByPaymentIntentIdOrderByCreatedAtAsc(UUID paymentIntentId);

    List<OutboxCommand> findByPaymentIntentIdAndCommandTypeOrderByCreatedAtAsc(UUID paymentIntentId, CommandType commandType);

    List<OutboxCommand> findByStatusOrderByCreatedAtAsc(CommandStatus status);

    List<OutboxCommand> findAllByOrderByCreatedAtDesc(Pageable page);

    long countByStatus(CommandStatus status);

    @Query("select min(c.createdAt) from OutboxCommand c where c.status = id.fatarc.portfolio.payflowreliability.outbox.CommandStatus.PENDING")
    Optional<Instant> oldestPendingCreatedAt();
}
