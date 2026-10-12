package id.fatarc.portfolio.payflowreliability.operations;

import id.fatarc.portfolio.payflowreliability.operations.dto.OperationsSummary;
import id.fatarc.portfolio.payflowreliability.operations.dto.PaymentCaseFile;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
public class OperationsController {
    private final OperationsService operationsService;

    public OperationsController(OperationsService operationsService) {
        this.operationsService = operationsService;
    }

    @GetMapping("/api/payment-intents/{paymentIntentId}/case-file")
    public PaymentCaseFile caseFile(@PathVariable UUID paymentIntentId) {
        return operationsService.caseFile(paymentIntentId);
    }

    @GetMapping("/api/operations/summary")
    public OperationsSummary summary() {
        return operationsService.summary();
    }
}
