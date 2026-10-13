package id.fatarc.portfolio.payflowreliability.ledger;

import id.fatarc.portfolio.payflowreliability.ledger.dto.BalanceView;
import id.fatarc.portfolio.payflowreliability.ledger.dto.InvariantReport;
import id.fatarc.portfolio.payflowreliability.ledger.dto.JournalView;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

/** Operator-only view of the books. */
@RestController
@RequestMapping("/api/ledger")
public class LedgerController {
    private final LedgerQueryService queryService;
    private final LedgerInvariantService invariantService;

    public LedgerController(LedgerQueryService queryService, LedgerInvariantService invariantService) {
        this.queryService = queryService;
        this.invariantService = invariantService;
    }

    @GetMapping("/balances")
    public List<BalanceView> balances(@RequestParam(required = false) String merchantId,
                                      @RequestParam(required = false) String currency) {
        return queryService.balances(merchantId, currency);
    }

    @GetMapping("/journals")
    public List<JournalView> journals(@RequestParam UUID paymentIntentId) {
        return queryService.journalsForPayment(paymentIntentId);
    }

    /** Runs the consistency check now. */
    @GetMapping("/invariants")
    public InvariantReport invariants() {
        return invariantService.check();
    }
}
