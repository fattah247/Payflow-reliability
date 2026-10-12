package id.fatarc.portfolio.payflowreliability.settlement;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import org.springframework.http.HttpStatus;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@Validated
@RestController
@RequestMapping("/api/settlements")
public class SettlementController {
    private final SettlementService settlementService;

    public SettlementController(SettlementService settlementService) {
        this.settlementService = settlementService;
    }

    /** {@code currency} is required only when the merchant has eligible payments in more than one currency. */
    @PostMapping("/batches")
    @ResponseStatus(HttpStatus.CREATED)
    public SettlementBatch createBatch(
            @RequestParam @NotBlank String merchantId,
            @RequestParam(required = false)
            @Pattern(regexp = "[A-Z]{3}", message = "must be a 3-letter uppercase ISO currency code") String currency) {
        return settlementService.createBatch(merchantId, currency);
    }

    @GetMapping("/batches")
    public List<SettlementBatch> listBatches(@RequestParam(required = false) String merchantId) {
        return settlementService.listBatches(merchantId);
    }

    @GetMapping("/batches/{batchId}/items")
    public List<SettlementBatchItem> listBatchItems(@PathVariable UUID batchId) {
        return settlementService.getItems(batchId);
    }
}
