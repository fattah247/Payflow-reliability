package id.fatarc.portfolio.payflowreliability.audit;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/** Recomputes a chain from stored values: any edited, deleted or reordered event is reported. */
@Service
public class AuditChainVerifier {
    private final AuditEventRepository repository;

    public AuditChainVerifier(AuditEventRepository repository) {
        this.repository = repository;
    }

    /**
     * @param firstBrokenSequence the first event that does not verify, or null when the chain is intact
     */
    public record Verification(String chainKey, int events, boolean valid, Long firstBrokenSequence, String problem,
                               String headHash) {
    }

    @Transactional(readOnly = true)
    public Verification verify(String chainKey) {
        List<AuditEvent> events = repository.findByChainKeyOrderBySequenceNumberAsc(chainKey);
        String expectedPrevious = AuditHash.GENESIS;
        long expectedSequence = 1;
        for (AuditEvent event : events) {
            if (event.getSequenceNumber() != expectedSequence) {
                return broken(chainKey, events, event, "Sequence " + expectedSequence + " is missing.");
            }
            if (!event.getPreviousHash().equals(expectedPrevious)) {
                return broken(chainKey, events, event, "Previous hash does not match the event before it.");
            }
            if (!event.computeHash().equals(event.getEventHash())) {
                return broken(chainKey, events, event, "Stored content does not match its hash: the event was modified.");
            }
            expectedPrevious = event.getEventHash();
            expectedSequence++;
        }
        return new Verification(chainKey, events.size(), true, null, null, expectedPrevious);
    }

    private static Verification broken(String chainKey, List<AuditEvent> events, AuditEvent event, String problem) {
        return new Verification(chainKey, events.size(), false, event.getSequenceNumber(), problem, null);
    }
}
