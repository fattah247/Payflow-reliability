package id.fatarc.portfolio.payflowreliability.bank;

import id.fatarc.portfolio.payflowreliability.common.DatabaseErrors;
import id.fatarc.portfolio.payflowreliability.common.Hashing;
import id.fatarc.portfolio.payflowreliability.reconciliation.PaymentReconciler;
import id.fatarc.portfolio.payflowreliability.reconciliation.PayoutMatcher;
import id.fatarc.portfolio.payflowreliability.reconciliation.breaks.BreakCategory;
import id.fatarc.portfolio.payflowreliability.reconciliation.breaks.BreakService;
import id.fatarc.portfolio.payflowreliability.settlement.SettlementBatchItem;
import id.fatarc.portfolio.payflowreliability.settlement.SettlementBatchItemRepository;
import id.fatarc.portfolio.payflowreliability.settlement.SettlementItemType;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Imports end-of-day statements. Safe to repeat and safe in any order:
 * <ul>
 *   <li><b>statement id</b>: the same statement again (same content hash) does nothing; the same id or sequence
 *       number with different content is a conflict, imported never, flagged always;</li>
 *   <li><b>line hash</b>: an entry already imported (for example in a re-issued statement) is skipped, so no
 *       bank movement is booked twice; a genuine second credit has its own bank reference and is booked;</li>
 *   <li><b>order</b>: each line is matched on its own merits, so a statement arriving before an earlier one
 *       books the same way; coverage (which days are complete) only advances over consecutive statements.</li>
 *   <li><b>the bank's own arithmetic</b>: each statement must add up (the parser checks), its opening balance must
 *       be its predecessor's closing balance, and it must not repeat entries already imported. Either problem
 *       opens a STATEMENT_CONFLICT break for treasury: the books are protected, the bank has to explain.</li>
 * </ul>
 * Every new line is booked exactly once by {@link PayoutMatcher}: to a payout, back to the provider, or to
 * suspense with a break. Imports of one account are serialized by its cursor row.
 */
@Service
public class BankStatementImportService {
    private static final Logger log = LoggerFactory.getLogger(BankStatementImportService.class);

    private final BankStatementRepository statementRepository;
    private final BankStatementLineRepository lineRepository;
    private final BankAccountCursorRepository cursorRepository;
    private final SettlementBatchItemRepository itemRepository;
    private final PayoutMatcher matcher;
    private final PaymentReconciler paymentReconciler;
    private final BreakService breakService;
    private final BankStatementFeed feed;
    private final MeterRegistry meterRegistry;
    private final Clock clock;
    private final TransactionTemplate transactions;
    private final long firstSequence;
    private final AtomicReference<Instant> lastImport = new AtomicReference<>();

    public BankStatementImportService(BankStatementRepository statementRepository, BankStatementLineRepository lineRepository,
                                      BankAccountCursorRepository cursorRepository, SettlementBatchItemRepository itemRepository,
                                      PayoutMatcher matcher, PaymentReconciler paymentReconciler, BreakService breakService,
                                      BankStatementFeed feed, MeterRegistry meterRegistry, Clock clock,
                                      PlatformTransactionManager transactionManager,
                                      @Value("${payflow.bank.first-statement-sequence:1}") long firstSequence) {
        this.statementRepository = statementRepository;
        this.lineRepository = lineRepository;
        this.cursorRepository = cursorRepository;
        this.itemRepository = itemRepository;
        this.matcher = matcher;
        this.paymentReconciler = paymentReconciler;
        this.breakService = breakService;
        this.feed = feed;
        this.meterRegistry = meterRegistry;
        this.clock = clock;
        this.transactions = new TransactionTemplate(transactionManager);
        this.firstSequence = firstSequence;
    }

    /** When a statement was last imported or confirmed already imported (dead man's switch for the bank feed). */
    public Optional<Instant> lastImport() {
        return Optional.ofNullable(lastImport.get());
    }

    public StatementImportResult importStatement(byte[] xml, String actor) {
        CamtStatement parsed = CamtStatementParser.parse(xml);
        String content = new String(xml, StandardCharsets.UTF_8);
        String contentHash = Hashing.sha256Hex(xml);
        Imported imported;
        try {
            imported = transactions.execute(status -> importInTransaction(parsed, content, contentHash, actor));
        } catch (DataIntegrityViolationException ex) {
            if (!DatabaseErrors.isUniqueViolation(ex)) {
                throw ex;
            }
            // A concurrent import created the account cursor or the statement first; evaluate again against it.
            imported = transactions.execute(status -> importInTransaction(parsed, content, contentHash, actor));
        }
        lastImport.set(clock.instant());
        if (!imported.landedPayments().isEmpty()) {
            paymentReconciler.reconcile(imported.landedPayments(), actor);
        }
        StatementImportResult result = imported.result();
        meterRegistry.counter("payflow.bank.statements.imported", "outcome", result.outcome().name()).increment();
        log.info("Bank statement {} seq={} outcome={} newLines={} landed={} suspense={} returns={} coveredThrough={}",
                result.statementId(), result.sequenceNumber(), result.outcome(), result.newLines(), result.landed().size(),
                result.suspense(), result.returns(), result.coveredThrough());
        return result;
    }

    /** Downloads every statement the bank has after what is already complete, and imports each (re-deliveries included). */
    public List<StatementImportResult> pull(String actor) {
        List<StatementImportResult> results = new ArrayList<>();
        for (String account : feed.accounts()) {
            long after = cursorRepository.findById(account).map(BankAccountCursor::getCoveredSequence).orElse(0L);
            for (BankStatementFeed.Listed listed : feed.statementsAfter(account, after)) {
                results.add(importStatement(feed.download(listed.statementId()), actor));
            }
        }
        return results;
    }

    private Imported importInTransaction(CamtStatement parsed, String content, String contentHash, String actor) {
        Instant now = clock.instant();
        BankAccountCursor cursor = cursorRepository.findForUpdate(parsed.accountId()).orElseGet(() ->
                cursorRepository.saveAndFlush(new BankAccountCursor(parsed.accountId(), parsed.currency(), firstSequence, now)));

        Optional<BankStatement> sameId = statementRepository.findByStatementId(parsed.statementId());
        if (sameId.isPresent()) {
            if (sameId.get().getContentHash().equals(contentHash)) {
                return new Imported(result(parsed, StatementImportResult.Outcome.ALREADY_IMPORTED, 0, 0, List.of(), 0, 0, 0,
                        cursor, "Statement " + parsed.statementId() + " was already imported; nothing was booked again."), Set.of());
            }
            return conflict(parsed, cursor, "Statement " + parsed.statementId()
                    + " was imported before with different content. Nothing from the new copy was imported.", actor);
        }
        Optional<BankStatement> sameSequence = statementRepository.findByAccountIdAndSequenceNumber(parsed.accountId(),
                parsed.sequenceNumber());
        if (sameSequence.isPresent()) {
            return conflict(parsed, cursor, "Account " + parsed.accountId() + " already has statement "
                    + sameSequence.get().getStatementId() + " as number " + parsed.sequenceNumber() + "; "
                    + parsed.statementId() + " claims the same number. Nothing from it was imported.", actor);
        }

        statementRepository.save(new BankStatement(parsed, contentHash, content, actor, now));
        int newLines = 0;
        int known = 0;
        int suspense = 0;
        int returns = 0;
        int cleared = 0;
        List<String> landed = new ArrayList<>();
        Set<UUID> landedPayments = new TreeSet<>();
        for (CamtStatement.Entry entry : parsed.entries()) {
            BankStatementLine line = new BankStatementLine(parsed.statementId(), parsed.accountId(), parsed.currency(), entry, now);
            if (lineRepository.existsByLineHash(line.getLineHash())) {
                known++;
                continue;
            }
            lineRepository.saveAndFlush(line);
            newLines++;
            PayoutMatcher.Outcome outcome = matcher.book(line, actor);
            switch (outcome.status()) {
                case MATCHED -> {
                    landed.add(entry.endToEndId());
                    itemRepository.findByBatchId(outcome.batchId()).stream()
                            .filter(item -> item.getItemType() == SettlementItemType.CAPTURE)
                            .map(SettlementBatchItem::getPaymentIntentId)
                            .forEach(landedPayments::add);
                }
                case SUSPENSE -> suspense++;
                case RETURN -> returns++;
                case CLEARED -> cleared++;
            }
        }
        checkBalanceChain(parsed, actor);
        if (known > 0) {
            breakService.open(BreakCategory.STATEMENT_CONFLICT, BreakService.statementSubject(parsed.statementId() + "#repeated"),
                    null, null, parsed.statementId(), parsed.currency(), null, BigDecimal.ZERO,
                    "Statement " + parsed.statementId() + " repeats " + known + " entr" + (known == 1 ? "y" : "ies")
                            + " already imported from another statement. They were not booked again; the bank's balances "
                            + "count them, so ask the bank which statement is authoritative.", actor);
        }
        SortedMap<Long, LocalDate> sequences = new TreeMap<>();
        statementRepository.findByAccountIdOrderBySequenceNumberAsc(parsed.accountId())
                .forEach(statement -> sequences.put(statement.getSequenceNumber(), statement.getStatementDate()));
        cursor.advance(sequences, now);
        return new Imported(result(parsed, StatementImportResult.Outcome.IMPORTED, newLines, known, landed, suspense, returns,
                cleared, cursor, null), landedPayments);
    }

    /** A statement's opening balance is its predecessor's closing balance, and its closing is its successor's opening. */
    private void checkBalanceChain(CamtStatement parsed, String actor) {
        statementRepository.findByAccountIdAndSequenceNumber(parsed.accountId(), parsed.sequenceNumber() - 1)
                .filter(previous -> previous.getClosingBalance().compareTo(parsed.openingBalance()) != 0)
                .ifPresent(previous -> balanceBreak(parsed, "opens at " + parsed.openingBalance().toPlainString() + ", but statement "
                        + previous.getStatementId() + " closed at " + previous.getClosingBalance().toPlainString(), actor));
        statementRepository.findByAccountIdAndSequenceNumber(parsed.accountId(), parsed.sequenceNumber() + 1)
                .filter(next -> next.getOpeningBalance().compareTo(parsed.closingBalance()) != 0)
                .ifPresent(next -> balanceBreak(parsed, "closes at " + parsed.closingBalance().toPlainString() + ", but statement "
                        + next.getStatementId() + " opens at " + next.getOpeningBalance().toPlainString(), actor));
    }

    private void balanceBreak(CamtStatement parsed, String problem, String actor) {
        breakService.open(BreakCategory.STATEMENT_CONFLICT, BreakService.statementSubject(parsed.statementId() + "#balance"),
                null, null, parsed.statementId(), parsed.currency(), null, BigDecimal.ZERO,
                "Statement " + parsed.statementId() + " " + problem + ": the bank's balance chain is broken.", actor);
    }

    private Imported conflict(CamtStatement parsed, BankAccountCursor cursor, String detail, String actor) {
        BigDecimal movement = parsed.closingBalance().subtract(parsed.openingBalance());
        breakService.open(BreakCategory.STATEMENT_CONFLICT, BreakService.statementSubject(parsed.statementId() + "#"
                        + parsed.sequenceNumber()), null, null, parsed.statementId(), parsed.currency(), movement.abs(),
                BigDecimal.ZERO, detail, actor);
        return new Imported(result(parsed, StatementImportResult.Outcome.CONFLICT, 0, 0, List.of(), 0, 0, 0, cursor, detail), Set.of());
    }

    private static StatementImportResult result(CamtStatement parsed, StatementImportResult.Outcome outcome, int newLines,
                                                int known, List<String> landed, int suspense, int returns, int cleared,
                                                BankAccountCursor cursor, String detail) {
        return new StatementImportResult(parsed.statementId(), parsed.accountId(), parsed.sequenceNumber(), parsed.date(),
                outcome, newLines, known, List.copyOf(landed), suspense, returns, cleared, cursor.getCoveredSequence(),
                cursor.getCoveredThrough(), detail);
    }

    private record Imported(StatementImportResult result, Set<UUID> landedPayments) {
    }
}
