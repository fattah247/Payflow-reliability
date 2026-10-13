package id.fatarc.portfolio.payflowreliability.audit;

import id.fatarc.portfolio.payflowreliability.common.BusinessException;
import id.fatarc.portfolio.payflowreliability.common.ErrorCode;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/audit-events")
public class AuditController {
    private final AuditService auditService;
    private final AuditChainVerifier chainVerifier;

    public AuditController(AuditService auditService, AuditChainVerifier chainVerifier) {
        this.auditService = auditService;
        this.chainVerifier = chainVerifier;
    }

    /**
     * Either {@code paymentIntentId} (every event about one payment, across aggregates) or
     * {@code aggregateType} + {@code aggregateId} (one aggregate's own events).
     */
    @GetMapping
    public List<AuditEvent> history(@RequestParam(required = false) UUID paymentIntentId,
                                    @RequestParam(required = false) String aggregateType,
                                    @RequestParam(required = false) String aggregateId) {
        if (paymentIntentId != null) {
            return auditService.paymentHistory(paymentIntentId);
        }
        if (isBlank(aggregateType) || isBlank(aggregateId)) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                    "Provide paymentIntentId, or both aggregateType and aggregateId.");
        }
        return auditService.history(aggregateType, aggregateId);
    }

    /** Recomputes a hash chain: a payment's ({@code paymentIntentId}) or any other ({@code chainKey}). */
    @GetMapping("/verify")
    public AuditChainVerifier.Verification verify(@RequestParam(required = false) UUID paymentIntentId,
                                                  @RequestParam(required = false) String chainKey) {
        if (paymentIntentId == null && isBlank(chainKey)) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "Provide paymentIntentId or chainKey.");
        }
        return chainVerifier.verify(paymentIntentId != null ? AuditHash.chainKey(paymentIntentId, null, null) : chainKey);
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
