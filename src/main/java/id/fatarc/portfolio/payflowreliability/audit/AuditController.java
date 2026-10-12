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

    public AuditController(AuditService auditService) {
        this.auditService = auditService;
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

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
