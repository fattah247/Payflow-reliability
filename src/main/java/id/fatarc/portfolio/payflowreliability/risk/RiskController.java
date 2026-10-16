package id.fatarc.portfolio.payflowreliability.risk;

import id.fatarc.portfolio.payflowreliability.common.BusinessException;
import id.fatarc.portfolio.payflowreliability.common.ErrorCode;
import id.fatarc.portfolio.payflowreliability.security.Caller;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.web.bind.annotation.*;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Operator view of the device-risk gate: decisions, labels learned since, and what the gate costs and saves. */
@RestController
@RequestMapping("/api/risk")
public class RiskController {
    private final RiskService riskService;
    private final RiskReportService reportService;

    public RiskController(RiskService riskService, RiskReportService reportService) {
        this.riskService = riskService;
        this.reportService = reportService;
    }

    public record Outcome(@NotNull UUID paymentIntentId, @NotNull RiskLabel label, @NotNull RiskLabel.Source source) {
    }

    public record Outcomes(@NotNull @Size(max = 5000) List<@Valid @NotNull Outcome> outcomes) {
    }

    @GetMapping("/decisions/{paymentIntentId}")
    public RiskDecision decision(@PathVariable UUID paymentIntentId) {
        return riskService.decisionFor(paymentIntentId).orElseThrow(() -> new BusinessException(ErrorCode.PAYMENT_INTENT_NOT_FOUND,
                "No risk decision for payment " + paymentIntentId));
    }

    /** What payments turned out to be (chargebacks, reports, or a simulation's ground truth). */
    @PostMapping("/outcomes")
    public Map<String, Integer> outcomes(@Valid @RequestBody Outcomes request, Caller caller) {
        int labelled = 0;
        for (Outcome outcome : request.outcomes()) {
            labelled += riskService.label(outcome.paymentIntentId(), outcome.label(), outcome.source(), caller.name()).isPresent() ? 1 : 0;
        }
        return Map.of("labelled", labelled);
    }

    /** Expires step-ups waiting longer than {@code olderThanSeconds} (default: the configured timeout). */
    @PostMapping("/expire-unconfirmed")
    public Map<String, Integer> expireUnconfirmed(@RequestParam(required = false) Long olderThanSeconds) {
        return Map.of("expired", riskService.expireUnconfirmed(olderThanSeconds == null ? null : Duration.ofSeconds(Math.max(0, olderThanSeconds))));
    }

    @GetMapping("/report")
    public RiskReportService.Report report(@RequestParam(defaultValue = "IDR") String currency) {
        return reportService.report(currency);
    }

    /** Moves one threshold through a range and shows what each value would trade: friction against fraud loss. */
    @GetMapping("/threshold-sweep")
    public RiskReportService.Sweep sweep(@RequestParam(defaultValue = "STEP_UP") RiskReportService.Parameter parameter,
                                         @RequestParam(defaultValue = "1") int from, @RequestParam(defaultValue = "7") int to,
                                         @RequestParam(defaultValue = "IDR") String currency) {
        if (from < 0 || to < from || to - from > 20) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "from and to must satisfy 0 <= from <= to <= from + 20.");
        }
        return reportService.sweep(parameter, from, to, currency);
    }
}
