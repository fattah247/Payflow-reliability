package id.fatarc.portfolio.payflowreliability.ledger;

import id.fatarc.portfolio.payflowreliability.common.BusinessException;
import id.fatarc.portfolio.payflowreliability.common.ErrorCode;
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

    /** A payment's journals ({@code paymentIntentId}), or those with one reference, such as a settlement batch id. */
    @GetMapping("/journals")
    public List<JournalView> journals(@RequestParam(required = false) UUID paymentIntentId,
                                      @RequestParam(required = false) String reference) {
        if ((paymentIntentId == null) == (reference == null)) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "Give exactly one of paymentIntentId and reference.");
        }
        return paymentIntentId != null ? queryService.journalsForPayment(paymentIntentId) : queryService.journalsByReference(reference);
    }

    /** Runs the consistency check now. */
    @GetMapping("/invariants")
    public InvariantReport invariants() {
        return invariantService.check();
    }
}
