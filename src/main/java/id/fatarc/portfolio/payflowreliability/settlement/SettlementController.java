package id.fatarc.portfolio.payflowreliability.settlement;

import id.fatarc.portfolio.payflowreliability.security.Caller;
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

    /** {@code currency} is required only when the merchant has payable items in more than one currency. */
    @PostMapping("/batches")
    @ResponseStatus(HttpStatus.CREATED)
    public SettlementBatch createBatch(
            @RequestParam @NotBlank String merchantId,
            @RequestParam(required = false)
            @Pattern(regexp = "[A-Z]{3}", message = "must be a 3-letter uppercase ISO currency code") String currency,
            Caller caller) {
        return settlementService.createBatch(merchantId, currency, caller);
    }

    /** Newest first; at most {@code limit} (default 100, at most 1000). */
    @GetMapping("/batches")
    public List<SettlementBatch> listBatches(@RequestParam(required = false) String merchantId,
                                             @RequestParam(required = false) Integer limit) {
        return settlementService.listBatches(merchantId, limit);
    }

    @GetMapping("/batches/{batchId}/items")
    public List<SettlementBatchItem> listBatchItems(@PathVariable UUID batchId) {
        return settlementService.getItems(batchId);
    }

    /** What would be paid out now, per merchant and currency. */
    @GetMapping("/backlog")
    public List<SettlementService.Backlog> backlog() {
        return settlementService.backlog();
    }
}
