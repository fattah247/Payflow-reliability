package id.fatarc.portfolio.payflowreliability.operations;

import id.fatarc.portfolio.payflowreliability.operations.dto.OperationsSummary;
import id.fatarc.portfolio.payflowreliability.operations.dto.PaymentCaseFile;
import id.fatarc.portfolio.payflowreliability.payment.PaymentStatusSweeper;
import org.springframework.web.bind.annotation.*;

import java.time.Duration;
import java.util.UUID;

@RestController
public class OperationsController {
    private final OperationsService operationsService;
    private final PaymentStatusSweeper sweeper;

    public OperationsController(OperationsService operationsService, PaymentStatusSweeper sweeper) {
        this.operationsService = operationsService;
        this.sweeper = sweeper;
    }

    @GetMapping("/api/payment-intents/{paymentIntentId}/case-file")
    public PaymentCaseFile caseFile(@PathVariable UUID paymentIntentId) {
        return operationsService.caseFile(paymentIntentId);
    }

    @GetMapping("/api/operations/summary")
    public OperationsSummary summary() {
        return operationsService.summary();
    }

    /** Asks the provider about payments waiting longer than {@code olderThanSeconds} (default: the sweeper threshold). */
    @PostMapping("/api/operations/sweeps/payment-status")
    public PaymentStatusSweeper.SweepReport sweep(@RequestParam(required = false) Long olderThanSeconds) {
        return olderThanSeconds == null ? sweeper.runOnce() : sweeper.runOnce(Duration.ofSeconds(Math.max(0, olderThanSeconds)));
    }
}
