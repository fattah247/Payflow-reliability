package id.fatarc.portfolio.payflowreliability.outbox;

import id.fatarc.portfolio.payflowreliability.common.Text;
import jakarta.persistence.*;

import java.time.Instant;
import java.util.UUID;

/**
 * A provider call recorded in the same transaction as the decision that needs it, then executed by
 * a worker. The idempotency key is sent to the provider, so resending after a crash or timeout can
 * never apply the operation twice.
 */
@Entity
@Table(name = "outbox_commands")
public class OutboxCommand {
    @Id
    private UUID id;

    @Version
    private Long version;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private CommandType commandType;

    @Column(nullable = false)
    private UUID paymentIntentId;

    /** The refund id for refunds, the payment id for reversals. */
    @Column(nullable = false)
    private String referenceId;

    @Column(nullable = false)
    private String idempotencyKey;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private CommandStatus status;

    @Column(nullable = false)
    private int attempts;

    @Column(nullable = false)
    private Instant nextAttemptAt;

    private String leaseOwner;

    private Instant leaseUntil;

    @Column(length = 1000)
    private String lastError;

    private String providerReference;

    /** When the first call was about to leave. From then on the provider may have acted on it. */
    private Instant dispatchedAt;

    /** The provider transaction the call is about, fixed at first dispatch so every resend is the same request. */
    private String providerTransactionId;

    @Column(nullable = false)
    private Instant createdAt;

    @Column(nullable = false)
    private Instant updatedAt;

    protected OutboxCommand() {
    }

    private OutboxCommand(CommandType commandType, UUID paymentIntentId, String referenceId, String idempotencyKey,
                          Instant now) {
        this.id = UUID.randomUUID();
        this.commandType = commandType;
        this.paymentIntentId = paymentIntentId;
        this.referenceId = referenceId;
        this.idempotencyKey = idempotencyKey;
        this.status = CommandStatus.PENDING;
        this.attempts = 0;
        this.nextAttemptAt = now;
        this.createdAt = now;
        this.updatedAt = now;
    }

    public static OutboxCommand refund(UUID paymentIntentId, UUID refundId, Instant now) {
        return new OutboxCommand(CommandType.REFUND, paymentIntentId, refundId.toString(), refundKey(refundId), now);
    }

    public static OutboxCommand reversal(UUID paymentIntentId, Instant now) {
        return reversal(paymentIntentId, 1, now);
    }

    /** The n-th reversal of a payment; only the first uses the plain key, later ones follow a refusal. */
    static OutboxCommand reversal(UUID paymentIntentId, int number, Instant now) {
        String key = number <= 1 ? reversalKey(paymentIntentId) : reversalKey(paymentIntentId) + "-" + number;
        return new OutboxCommand(CommandType.REVERSAL, paymentIntentId, paymentIntentId.toString(), key, now);
    }

    public static String refundKey(UUID refundId) {
        return "refund-" + refundId;
    }

    public static String reversalKey(UUID paymentIntentId) {
        return "reversal-" + paymentIntentId;
    }

    /**
     * Never handed to the provider and not being handed over right now. Dispatch is committed before the
     * call leaves, so a worker that crashes after the provider acted still leaves this false.
     */
    public boolean isUnsent(Instant now) {
        return status == CommandStatus.PENDING && dispatchedAt == null && (leaseUntil == null || leaseUntil.isBefore(now));
    }

    public boolean wasDispatched() {
        return dispatchedAt != null;
    }

    /** Called, and committed, before the call leaves; pins the provider transaction for every later resend. */
    void markDispatched(String providerTransactionId, Instant now) {
        if (dispatchedAt == null) {
            dispatchedAt = now;
        }
        if (this.providerTransactionId == null) {
            this.providerTransactionId = providerTransactionId;
        }
        updatedAt = now;
    }

    void recordAttempt(Instant now) {
        attempts++;
        updatedAt = now;
    }

    void succeed(String providerReference, Instant now) {
        this.status = CommandStatus.SUCCEEDED;
        this.providerReference = providerReference;
        this.lastError = null;
        releaseLease(now);
    }

    void fail(String reason, Instant now) {
        this.status = CommandStatus.FAILED;
        this.lastError = Text.fit(reason, 1000);
        releaseLease(now);
    }

    void retryLater(String reason, Instant nextAttemptAt, Instant now) {
        this.lastError = Text.fit(reason, 1000);
        this.nextAttemptAt = nextAttemptAt;
        releaseLease(now);
    }

    void escalate(String reason, Instant now) {
        this.status = CommandStatus.NEEDS_REVIEW;
        this.lastError = Text.fit(reason, 1000);
        releaseLease(now);
    }

    public void cancel(Instant now) {
        this.status = CommandStatus.CANCELLED;
        releaseLease(now);
    }

    /**
     * Operator retry of a call with an unknown outcome: safe because the provider deduplicates by idempotency
     * key, and the dispatch mark is kept so the next attempt asks the provider before resending.
     */
    void requeue(Instant now) {
        this.status = CommandStatus.PENDING;
        this.attempts = 0;
        this.nextAttemptAt = now;
        releaseLease(now);
    }

    private void releaseLease(Instant now) {
        this.leaseOwner = null;
        this.leaseUntil = null;
        this.updatedAt = now;
    }

    public UUID getId() { return id; }
    public Long getVersion() { return version; }
    public CommandType getCommandType() { return commandType; }
    public UUID getPaymentIntentId() { return paymentIntentId; }
    public String getReferenceId() { return referenceId; }
    public String getIdempotencyKey() { return idempotencyKey; }
    public CommandStatus getStatus() { return status; }
    public int getAttempts() { return attempts; }
    public Instant getNextAttemptAt() { return nextAttemptAt; }
    public String getLeaseOwner() { return leaseOwner; }
    public Instant getLeaseUntil() { return leaseUntil; }
    public String getLastError() { return lastError; }
    public String getProviderReference() { return providerReference; }
    public Instant getDispatchedAt() { return dispatchedAt; }
    public String getProviderTransactionId() { return providerTransactionId; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
