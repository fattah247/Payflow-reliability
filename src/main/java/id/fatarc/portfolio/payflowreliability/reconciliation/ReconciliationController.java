package id.fatarc.portfolio.payflowreliability.reconciliation;

import id.fatarc.portfolio.payflowreliability.common.PageCursor;
import id.fatarc.portfolio.payflowreliability.common.PageResponses;
import id.fatarc.portfolio.payflowreliability.reconciliation.breaks.BreakCategory;
import id.fatarc.portfolio.payflowreliability.reconciliation.breaks.BreakResolutionService;
import id.fatarc.portfolio.payflowreliability.reconciliation.breaks.BreakStatus;
import id.fatarc.portfolio.payflowreliability.reconciliation.breaks.BreakView;
import id.fatarc.portfolio.payflowreliability.reconciliation.breaks.ResolveBreakRequest;
import id.fatarc.portfolio.payflowreliability.reconciliation.dto.ReconciliationReportRequest;
import id.fatarc.portfolio.payflowreliability.reconciliation.dto.ReconciliationReportResponse;
import id.fatarc.portfolio.payflowreliability.security.Caller;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/reconciliation")
public class ReconciliationController {
    private final ReconciliationService reconciliationService;
    private final ThreeWayReconciliationService threeWayService;
    private final SettlementCertaintyService certaintyService;
    private final BreakResolutionService breakResolutionService;

    public ReconciliationController(ReconciliationService reconciliationService, ThreeWayReconciliationService threeWayService,
                                    SettlementCertaintyService certaintyService, BreakResolutionService breakResolutionService) {
        this.reconciliationService = reconciliationService;
        this.threeWayService = threeWayService;
        this.certaintyService = certaintyService;
        this.breakResolutionService = breakResolutionService;
    }

    /** Runs the time-based part of the three-way match now: suspense retries, missing credits, ready payments. */
    @PostMapping("/three-way/run")
    public ThreeWayReconciliationService.RunReport runThreeWay(Caller caller) {
        return threeWayService.run(caller.name());
    }

    /** The three legs of one payout side by side (ledger, provider, bank), with every check spelled out. */
    @GetMapping("/three-way/batches/{batchId}")
    public SettlementCertaintyService.BatchCertainty batchCertainty(@PathVariable UUID batchId) {
        return certaintyService.batch(batchId);
    }

    /** ?status=OPEN|RESOLVED (default: all), optionally one category or one batch. Oldest first when filtered by status. */
    @GetMapping("/breaks")
    public ResponseEntity<List<BreakView>> breaks(@RequestParam(required = false) BreakStatus status,
                                                  @RequestParam(required = false) BreakCategory category,
                                                  @RequestParam(required = false) UUID batchId,
                                                  @RequestParam(required = false) Integer limit,
                                                  @RequestParam(required = false) String after) {
        return PageResponses.of(breakResolutionService.page(status, category, batchId, PageCursor.decode(after), limit));
    }

    @GetMapping("/breaks/{breakId}")
    public BreakView getBreak(@PathVariable UUID breakId) {
        return breakResolutionService.get(breakId);
    }

    @PostMapping("/breaks/{breakId}/resolve")
    public BreakView resolveBreak(@PathVariable UUID breakId, @Valid @RequestBody ResolveBreakRequest request, Caller caller) {
        return breakResolutionService.resolve(breakId, request, caller);
    }

    @PostMapping("/reports")
    public ReconciliationReportResponse importReport(@Valid @RequestBody ReconciliationReportRequest request, Caller caller) {
        return reconciliationService.importReport(request, caller);
    }
}
