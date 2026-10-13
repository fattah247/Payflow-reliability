package id.fatarc.portfolio.payflowreliability.outbox;

import id.fatarc.portfolio.payflowreliability.security.Caller;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

/** Operator view of provider calls: what is pending, what failed, what needs a decision. */
@RestController
@RequestMapping("/api/outbox")
public class OutboxController {
    private final OutboxService outboxService;
    private final OutboxWorker outboxWorker;

    public OutboxController(OutboxService outboxService, OutboxWorker outboxWorker) {
        this.outboxService = outboxService;
        this.outboxWorker = outboxWorker;
    }

    @GetMapping
    public List<OutboxCommand> list(@RequestParam(required = false) CommandStatus status) {
        return outboxService.list(status);
    }

    @GetMapping("/{commandId}")
    public OutboxCommand get(@PathVariable UUID commandId) {
        return outboxService.getRequired(commandId);
    }

    @PostMapping("/{commandId}/retry")
    public OutboxCommand retry(@PathVariable UUID commandId, Caller caller) {
        return outboxService.retry(commandId, caller);
    }

    /** Runs the worker now instead of waiting for its schedule (local use and incident response). */
    @PostMapping("/run")
    public RunResult run() {
        return new RunResult(outboxWorker.runOnce());
    }

    public record RunResult(int processed) {
    }
}
