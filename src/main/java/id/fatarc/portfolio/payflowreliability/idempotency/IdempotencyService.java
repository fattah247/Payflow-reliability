package id.fatarc.portfolio.payflowreliability.idempotency;

import id.fatarc.portfolio.payflowreliability.common.DatabaseErrors;
import id.fatarc.portfolio.payflowreliability.common.ErrorCode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

/**
 * Stores the outcome of requests sent with an {@code Idempotency-Key} so a retry gets the original
 * response instead of repeating the operation. Keys are scoped to the authenticated caller.
 */
@Service
public class IdempotencyService {
    private final IdempotencyRecordRepository repository;
    private final TransactionTemplate transactions;
    private final Clock clock;
    private final Duration retention;
    private final Duration staleAfter;

    public IdempotencyService(IdempotencyRecordRepository repository, PlatformTransactionManager transactionManager,
                              Clock clock,
                              @Value("${payflow.idempotency.retention:24h}") Duration retention,
                              @Value("${payflow.idempotency.stale-after:60s}") Duration staleAfter) {
        this.repository = repository;
        this.transactions = new TransactionTemplate(transactionManager);
        this.clock = clock;
        this.retention = retention;
        this.staleAfter = staleAfter;
    }

    public sealed interface Decision permits Proceed, Replay, Reject {
    }

    public record Proceed(UUID recordId) implements Decision {
    }

    public record Replay(IdempotencyRecord record) implements Decision {
    }

    public record Reject(ErrorCode code, String message) implements Decision {
    }

    public Decision begin(String scope, String key, String method, String path, String requestHash) {
        try {
            return transactions.execute(status -> beginInTransaction(scope, key, method, path, requestHash));
        } catch (DataIntegrityViolationException ex) {
            if (!DatabaseErrors.isUniqueViolation(ex)) {
                throw ex;
            }
            // A concurrent request with the same key inserted first: evaluate against its record.
            return transactions.execute(status -> beginInTransaction(scope, key, method, path, requestHash));
        }
    }

    public void complete(UUID recordId, int status, String contentType, String location, String body) {
        transactions.executeWithoutResult(tx -> repository.findById(recordId)
                .ifPresent(record -> record.complete(status, contentType, location, body, clock.instant())));
    }

    /** Forget an attempt whose outcome must not be replayed (validation errors, retryable conflicts, 5xx). */
    public void abandon(UUID recordId) {
        transactions.executeWithoutResult(tx -> repository.deleteById(recordId));
    }

    public int deleteExpired() {
        Integer deleted = transactions.execute(status -> repository.deleteExpired(clock.instant()));
        return deleted == null ? 0 : deleted;
    }

    private Decision beginInTransaction(String scope, String key, String method, String path, String requestHash) {
        Instant now = clock.instant();
        var existing = repository.findByScopeAndIdempotencyKey(scope, key);
        if (existing.isEmpty()) {
            IdempotencyRecord created = repository.saveAndFlush(
                    new IdempotencyRecord(scope, key, method, path, requestHash, now, now.plus(retention)));
            return new Proceed(created.getId());
        }
        IdempotencyRecord record = existing.get();
        if (record.getExpiresAt().isBefore(now)) {
            // Two retries of an expired key may both get here; the loser's insert hits the unique constraint
            // and begin() evaluates again against the winner's record.
            repository.deleteIfExpired(record.getId(), now);
            IdempotencyRecord created = repository.saveAndFlush(
                    new IdempotencyRecord(scope, key, method, path, requestHash, now, now.plus(retention)));
            return new Proceed(created.getId());
        }
        if (!record.getRequestHash().equals(requestHash)) {
            return new Reject(ErrorCode.IDEMPOTENCY_KEY_REUSED,
                    "Idempotency-Key " + key + " was already used for a different request (" + record.getRequestMethod()
                            + " " + record.getRequestPath() + ").");
        }
        if (record.getStatus() == IdempotencyRecord.Status.COMPLETED) {
            return new Replay(record);
        }
        if (repository.takeOverStale(record.getId(), now, now.minus(staleAfter)) == 1) {
            return new Proceed(record.getId());
        }
        return new Reject(ErrorCode.IDEMPOTENCY_KEY_IN_PROGRESS,
                "A request with Idempotency-Key " + key + " is still being processed. Retry shortly.");
    }
}
