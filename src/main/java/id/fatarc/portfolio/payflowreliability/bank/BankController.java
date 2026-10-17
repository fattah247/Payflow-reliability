package id.fatarc.portfolio.payflowreliability.bank;

import id.fatarc.portfolio.payflowreliability.common.ListLimit;
import id.fatarc.portfolio.payflowreliability.security.Caller;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/** Operator view of the settlement account: import statements, pull them from the bank, inspect lines. */
@RestController
@RequestMapping("/api/bank")
public class BankController {
    private final BankStatementImportService importService;
    private final BankStatementRepository statementRepository;
    private final BankStatementLineRepository lineRepository;

    public BankController(BankStatementImportService importService, BankStatementRepository statementRepository,
                          BankStatementLineRepository lineRepository) {
        this.importService = importService;
        this.statementRepository = statementRepository;
        this.lineRepository = lineRepository;
    }

    /** Imports one camt.053 file. 200 for IMPORTED or ALREADY_IMPORTED, 409 for a CONFLICT (a break is opened). */
    @PostMapping(value = "/statements", consumes = {MediaType.APPLICATION_XML_VALUE, MediaType.TEXT_XML_VALUE})
    public ResponseEntity<StatementImportResult> importStatement(@RequestBody byte[] statement, Caller caller) {
        StatementImportResult result = importService.importStatement(statement, caller.name());
        return ResponseEntity.status(result.outcome() == StatementImportResult.Outcome.CONFLICT ? HttpStatus.CONFLICT : HttpStatus.OK)
                .body(result);
    }

    /** Downloads and imports every statement the bank has that is not complete here yet. */
    @PostMapping("/statements/pull")
    public List<StatementImportResult> pull(Caller caller) {
        return importService.pull(caller.name());
    }

    /** Imported statements, newest first: at most {@code limit} (default 100, at most 1000). */
    @GetMapping("/statements")
    @Transactional(readOnly = true)
    public List<Map<String, Object>> statements(@RequestParam(required = false) Integer limit) {
        return statementRepository.findAllByOrderByImportedAtDesc(ListLimit.first(limit)).stream().map(statement -> Map.<String, Object>of(
                "statementId", statement.getStatementId(),
                "accountId", statement.getAccountId(),
                "sequenceNumber", statement.getSequenceNumber(),
                "statementDate", statement.getStatementDate(),
                "openingBalance", statement.getOpeningBalance(),
                "closingBalance", statement.getClosingBalance(),
                "entryCount", statement.getEntryCount(),
                "importedAt", statement.getImportedAt())).toList();
    }

    /**
     * Imported bank lines: those carrying one payout reference ({@code endToEndId}), those of one statement, or those
     * with one match status; at most {@code limit} (default 100, at most 1000). Without a filter, newest first.
     */
    @GetMapping("/lines")
    @Transactional(readOnly = true)
    public List<BankStatementLine> lines(@RequestParam(required = false) BankLineStatus status,
                                         @RequestParam(required = false) String endToEndId,
                                         @RequestParam(required = false) String statementId,
                                         @RequestParam(required = false) Integer limit) {
        int max = ListLimit.of(limit);
        List<BankStatementLine> candidates = endToEndId != null ? lineRepository.findByEndToEndIdOrderByImportedAtAsc(endToEndId)
                : statementId != null ? lineRepository.findByStatementIdOrderByValueDateAscAccountServicerRefAsc(statementId)
                : status != null ? lineRepository.findByMatchStatusOrderByImportedAtAsc(status, ListLimit.first(max))
                : lineRepository.findAllByOrderByImportedAtDesc(ListLimit.first(max));
        return candidates.stream()
                .filter(line -> status == null || line.getMatchStatus() == status)
                .filter(line -> statementId == null || statementId.equals(line.getStatementId()))
                .limit(max)
                .toList();
    }
}
