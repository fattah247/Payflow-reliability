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
    private final MoneyInvariantService moneyInvariantService;
    private final MoneyAtRiskService moneyAtRiskService;

    public OperationsController(OperationsService operationsService, PaymentStatusSweeper sweeper,
                                MoneyInvariantService moneyInvariantService, MoneyAtRiskService moneyAtRiskService) {
        this.operationsService = operationsService;
        this.sweeper = sweeper;
        this.moneyInvariantService = moneyInvariantService;
        this.moneyAtRiskService = moneyAtRiskService;
    }

    /** Money at risk now, by kind, as counts and amounts; for one merchant when {@code merchantId} is given. */
    @GetMapping("/api/operations/money-at-risk")
    public MoneyAtRiskService.MoneyAtRisk moneyAtRisk(@RequestParam(required = false) String merchantId) {
        return moneyAtRiskService.compute(merchantId);
    }

    /** Checks every money rule now, in one snapshot, with the evidence of each violation. */
    @GetMapping("/api/operations/invariants")
    public MoneyInvariantService.Report invariants() {
        return moneyInvariantService.check();
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
