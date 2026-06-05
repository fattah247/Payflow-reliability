package id.fatarc.portfolio.payflowreliability.audit;

import jakarta.validation.constraints.NotBlank;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@Validated
@RestController
@RequestMapping("/api/audit-events")
public class AuditController {
    private final AuditService auditService;

    public AuditController(AuditService auditService) {
        this.auditService = auditService;
    }

    @GetMapping
    public List<AuditEvent> history(@RequestParam @NotBlank String aggregateType,
                                    @RequestParam @NotBlank String aggregateId) {
        return auditService.history(aggregateType, aggregateId);
    }
}
