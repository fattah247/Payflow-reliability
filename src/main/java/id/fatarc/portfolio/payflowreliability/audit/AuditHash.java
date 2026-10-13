package id.fatarc.portfolio.payflowreliability.audit;

import id.fatarc.portfolio.payflowreliability.common.Hashing;

import java.time.Instant;
import java.util.UUID;

/**
 * Hash of one audit event, committing to the previous event in its chain. Changing, deleting or
 * reordering any stored event breaks every later hash. Fields are length-prefixed so no two
 * different events can produce the same input.
 */
public final class AuditHash {
    public static final String GENESIS = "0".repeat(64);

    private AuditHash() {
    }

    public static String compute(String chainKey, long sequenceNumber, String previousHash, UUID id,
                                 String aggregateType, String aggregateId, UUID paymentIntentId, String eventType,
                                 String previousState, String newState, String actor, String reason,
                                 String metadata, String requestId, Instant createdAt) {
        StringBuilder input = new StringBuilder(512);
        for (Object field : new Object[]{chainKey, sequenceNumber, previousHash, id, aggregateType, aggregateId,
                paymentIntentId, eventType, previousState, newState, actor, reason, metadata, requestId, createdAt}) {
            if (field == null) {
                input.append("~|");
            } else {
                String value = field.toString();
                input.append(value.length()).append(':').append(value).append('|');
            }
        }
        return Hashing.sha256Hex(input.toString());
    }

    /** Payment events chain per payment; other events chain per aggregate. */
    public static String chainKey(UUID paymentIntentId, String aggregateType, String aggregateId) {
        return paymentIntentId != null ? "payment:" + paymentIntentId : aggregateType + ":" + aggregateId;
    }
}
