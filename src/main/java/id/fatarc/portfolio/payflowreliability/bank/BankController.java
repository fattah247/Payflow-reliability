package id.fatarc.portfolio.payflowreliability.bank;

import id.fatarc.portfolio.payflowreliability.common.Keyset;
import id.fatarc.portfolio.payflowreliability.common.PageCursor;
import id.fatarc.portfolio.payflowreliability.common.PageResponses;
import id.fatarc.portfolio.payflowreliability.security.Caller;
import org.springframework.data.jpa.domain.Specification;
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

    /** Imported statements, newest first, a page at a time ({@code limit}, {@code after}). */
    @GetMapping("/statements")
    @Transactional(readOnly = true)
    public ResponseEntity<List<Map<String, Object>>> statements(@RequestParam(required = false) Integer limit,
                                                                @RequestParam(required = false) String after) {
        return PageResponses.of(Keyset.page(statementRepository, Keyset.all(), "importedAt", false, PageCursor.decode(after), limit,
                        statement -> new PageCursor(statement.getImportedAt(), statement.getId()))
                .map(rows -> rows.stream().map(statement -> Map.<String, Object>of(
                        "statementId", statement.getStatementId(),
                        "accountId", statement.getAccountId(),
                        "sequenceNumber", statement.getSequenceNumber(),
                        "statementDate", statement.getStatementDate(),
                        "openingBalance", statement.getOpeningBalance(),
                        "closingBalance", statement.getClosingBalance(),
                        "entryCount", statement.getEntryCount(),
                        "importedAt", statement.getImportedAt())).toList()));
    }

    /**
     * Imported bank lines, a page at a time: those carrying one payout reference ({@code endToEndId}), those of one
     * statement, and/or those with one match status, oldest first; without a filter, newest first.
     */
    @GetMapping("/lines")
    @Transactional(readOnly = true)
    public ResponseEntity<List<BankStatementLine>> lines(@RequestParam(required = false) BankLineStatus status,
                                                         @RequestParam(required = false) String endToEndId,
                                                         @RequestParam(required = false) String statementId,
                                                         @RequestParam(required = false) Integer limit,
                                                         @RequestParam(required = false) String after) {
        Specification<BankStatementLine> filter = Keyset.all();
        if (status != null) {
            filter = filter.and((root, query, cb) -> cb.equal(root.get("matchStatus"), status));
        }
        if (endToEndId != null) {
            filter = filter.and((root, query, cb) -> cb.equal(root.get("endToEndId"), endToEndId));
        }
        if (statementId != null) {
            filter = filter.and((root, query, cb) -> cb.equal(root.get("statementId"), statementId));
        }
        boolean filtered = status != null || endToEndId != null || statementId != null;
        return PageResponses.of(Keyset.page(lineRepository, filter, "importedAt", filtered, PageCursor.decode(after), limit,
                line -> new PageCursor(line.getImportedAt(), line.getId())));
    }
}
