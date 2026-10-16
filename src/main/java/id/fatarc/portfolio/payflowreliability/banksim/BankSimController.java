package id.fatarc.portfolio.payflowreliability.banksim;

import id.fatarc.portfolio.payflowreliability.common.Hashing;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The bank's statement interface (what a real bank offers over EBICS, SFTP or an API) plus simulation controls:
 * closing business days and injecting faults. Authenticated with the bank simulator's own key.
 */
@RestController
@RequestMapping("/bank-sim/v1")
@ConditionalOnProperty(name = "payflow.bank-sim.enabled", havingValue = "true")
public class BankSimController {
    private final BankSimulator simulator;
    private final BankFaults faults;
    private final BankSimProperties properties;

    public BankSimController(BankSimulator simulator, BankFaults faults, BankSimProperties properties) {
        this.simulator = simulator;
        this.faults = faults;
        this.properties = properties;
    }

    public record FaultRequest(BankFaults.Mode mode, Integer times, Integer days, BigDecimal amount) {
    }

    public record CloseRequest(String currency, LocalDate through) {
    }

    // ---- what a bank offers: accounts and statements ---------------------------------------------

    @GetMapping("/accounts")
    public List<Map<String, Object>> accounts(@RequestHeader(value = "Authorization", required = false) String authorization) {
        authenticate(authorization);
        return simulator.accounts().stream().map(account -> {
            Map<String, Object> view = new LinkedHashMap<>();
            view.put("accountId", account.getAccountId());
            view.put("currency", account.getCurrency());
            view.put("businessDate", account.getBusinessDate());
            view.put("nextSequence", account.getNextSequence());
            view.put("balance", account.getBalance());
            return view;
        }).toList();
    }

    @GetMapping("/accounts/{accountId}/statements")
    public List<Map<String, Object>> statements(@RequestHeader(value = "Authorization", required = false) String authorization,
                                                @PathVariable String accountId,
                                                @RequestParam(defaultValue = "0") long afterSequence) {
        authenticate(authorization);
        return simulator.statementsAfter(accountId, afterSequence).stream().map(statement -> {
            Map<String, Object> view = new LinkedHashMap<>();
            view.put("statementId", statement.getStatementId());
            view.put("sequenceNumber", statement.getSequenceNumber());
            view.put("statementDate", statement.getStatementDate());
            return view;
        }).toList();
    }

    @GetMapping(value = "/statements/{statementId}", produces = MediaType.APPLICATION_XML_VALUE)
    public String statement(@RequestHeader(value = "Authorization", required = false) String authorization,
                            @PathVariable String statementId) {
        authenticate(authorization);
        return simulator.statement(statementId).map(BankSimStatement::getContent)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Unknown statement " + statementId));
    }

    // ---- simulation controls: the passing of business days and what goes wrong ---------------------

    @PostMapping("/days/close")
    public List<Map<String, Object>> closeDays(@RequestHeader(value = "Authorization", required = false) String authorization,
                                               @RequestBody CloseRequest request) {
        authenticate(authorization);
        if (request.currency() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "currency is required");
        }
        return simulator.closeDays(request.currency(), request.through()).stream().map(statement -> {
            Map<String, Object> view = new LinkedHashMap<>();
            view.put("statementId", statement.getStatementId());
            view.put("sequenceNumber", statement.getSequenceNumber());
            view.put("statementDate", statement.getStatementDate());
            return view;
        }).toList();
    }

    @PostMapping("/faults")
    public Map<String, Object> injectFault(@RequestHeader(value = "Authorization", required = false) String authorization,
                                           @RequestBody FaultRequest request) {
        authenticate(authorization);
        int times = request.times() == null ? 1 : request.times();
        faults.inject(request.mode(), times, request.days(), request.amount());
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("mode", request.mode());
        view.put("times", times);
        return view;
    }

    @DeleteMapping("/faults")
    public ResponseEntity<Void> clearFaults(@RequestHeader(value = "Authorization", required = false) String authorization) {
        authenticate(authorization);
        faults.clear();
        return ResponseEntity.noContent().build();
    }

    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<Map<String, Object>> handle(ResponseStatusException ex) {
        return ResponseEntity.status(ex.getStatusCode()).contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("error", String.valueOf(ex.getReason())));
    }

    private void authenticate(String authorization) {
        if (!Hashing.constantTimeEquals("Bearer " + properties.apiKey(), authorization)) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid bank API key");
        }
    }
}
