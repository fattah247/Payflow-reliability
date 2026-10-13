package id.fatarc.portfolio.payflowreliability.idempotency;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface IdempotencyRecordRepository extends JpaRepository<IdempotencyRecord, UUID> {
    Optional<IdempotencyRecord> findByScopeAndIdempotencyKey(String scope, String idempotencyKey);

    /** Atomically takes over an attempt that was abandoned (for example the server died mid-request). */
    @Modifying
    @Query("""
            update IdempotencyRecord r set r.createdAt = :now
            where r.id = :id and r.status = id.fatarc.portfolio.payflowreliability.idempotency.IdempotencyRecord.Status.IN_PROGRESS
              and r.createdAt < :staleBefore
            """)
    int takeOverStale(@Param("id") UUID id, @Param("now") Instant now, @Param("staleBefore") Instant staleBefore);

    @Modifying
    @Query("delete from IdempotencyRecord r where r.expiresAt < :now")
    int deleteExpired(@Param("now") Instant now);

    /** A bulk delete: removing a row a concurrent request already removed is a no-op, not a stale-state error. */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("delete from IdempotencyRecord r where r.id = :id and r.expiresAt < :now")
    int deleteIfExpired(@Param("id") UUID id, @Param("now") Instant now);
}
