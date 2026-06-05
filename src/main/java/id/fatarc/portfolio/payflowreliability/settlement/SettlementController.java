package id.fatarc.portfolio.payflowreliability.settlement;

import jakarta.validation.constraints.NotBlank;
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

    @PostMapping("/batches")
    public SettlementBatch createBatch(@RequestParam @NotBlank String merchantId) {
        return settlementService.createBatch(merchantId);
    }

    @GetMapping("/batches")
    public List<SettlementBatch> listBatches() {
        return settlementService.listBatches();
    }

    @GetMapping("/batches/{batchId}/items")
    public List<SettlementBatchItem> listBatchItems(@PathVariable UUID batchId) {
        return settlementService.getItems(batchId);
    }
}
