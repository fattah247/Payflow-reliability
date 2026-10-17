package id.fatarc.portfolio.payflowreliability.settlement;

import id.fatarc.portfolio.payflowreliability.common.PageCursor;
import id.fatarc.portfolio.payflowreliability.common.PageResponses;
import id.fatarc.portfolio.payflowreliability.security.Caller;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
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

    /** Newest first, a page at a time ({@code limit}, {@code after}). */
    @GetMapping("/batches")
    public ResponseEntity<List<SettlementBatch>> listBatches(@RequestParam(required = false) String merchantId,
                                                             @RequestParam(required = false) Integer limit,
                                                             @RequestParam(required = false) String after) {
        return PageResponses.of(settlementService.listBatches(merchantId, PageCursor.decode(after), limit));
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
