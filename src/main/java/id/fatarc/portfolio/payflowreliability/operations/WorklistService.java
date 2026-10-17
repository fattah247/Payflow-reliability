package id.fatarc.portfolio.payflowreliability.operations;

import jakarta.persistence.EntityManager;
import id.fatarc.portfolio.payflowreliability.bank.BankStatementLine;
import id.fatarc.portfolio.payflowreliability.bank.BankStatementLineRepository;
import id.fatarc.portfolio.payflowreliability.common.BusinessException;
import id.fatarc.portfolio.payflowreliability.common.ErrorCode;
import id.fatarc.portfolio.payflowreliability.common.Keyset;
import id.fatarc.portfolio.payflowreliability.common.ListLimit;
import id.fatarc.portfolio.payflowreliability.common.Money;
import id.fatarc.portfolio.payflowreliability.common.PageCursor;
import id.fatarc.portfolio.payflowreliability.common.PageSlice;
import id.fatarc.portfolio.payflowreliability.payment.PaymentIntent;
import id.fatarc.portfolio.payflowreliability.payment.PaymentIntentRepository;
import id.fatarc.portfolio.payflowreliability.reconciliation.breaks.BreakAction;
import id.fatarc.portfolio.payflowreliability.reconciliation.breaks.BreakCategory;
import id.fatarc.portfolio.payflowreliability.reconciliation.breaks.BreakOwner;
import id.fatarc.portfolio.payflowreliability.reconciliation.breaks.BreakStatus;
import id.fatarc.portfolio.payflowreliability.reconciliation.breaks.BreakView;
import id.fatarc.portfolio.payflowreliability.reconciliation.breaks.ReconciliationBreak;
import id.fatarc.portfolio.payflowreliability.reconciliation.breaks.ReconciliationBreakRepository;
import id.fatarc.portfolio.payflowreliability.review.ReviewAction;
import id.fatarc.portfolio.payflowreliability.review.ReviewCase;
import id.fatarc.portfolio.payflowreliability.review.ReviewCaseRepository;
import id.fatarc.portfolio.payflowreliability.review.ReviewStatus;
import id.fatarc.portfolio.payflowreliability.review.ReviewWorkflowService;
import id.fatarc.portfolio.payflowreliability.review.dto.ReviewCaseView;
import id.fatarc.portfolio.payflowreliability.settlement.BrokenReason;
import id.fatarc.portfolio.payflowreliability.settlement.PayoutStatus;
import id.fatarc.portfolio.payflowreliability.settlement.SettlementBatch;
import id.fatarc.portfolio.payflowreliability.settlement.SettlementBatchRepository;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Everything that is waiting for a person, in one queue, most urgent first: unresolved review cases, proposals waiting
 * for a second operator, and open reconciliation breaks. Each item says who should act, what money is involved, by
 * when, what to do next, and the exact calls that do it, so an operator moves from "something needs attention" to
 * "resolved" without assembling the picture from several endpoints.
 *
 * <p>Due times are stored on each case and break and follow the alerts that would page or ticket if the item waited
 * longer: a review case within 4 hours ({@code PayflowReviewQueueAging}), a provider call with an unknown outcome
 * within 5 minutes ({@code PayflowProviderCallOutcomeUnknown}), a proposal within 1 hour ({@code PayflowApprovalWaiting}),
 * and a break by its category's own due time ({@code PayflowReconciliationBreaksOverdue}).
 */
@Service
public class WorklistService {
    public enum Kind { REVIEW_CASE, APPROVAL, BREAK }

    /** Who should act: operations (any operator), or the team that owns a reconciliation break. */
    public enum Owner { OPERATIONS, TREASURY, PROVIDER_OPERATIONS }

    /**
     * A call that acts on the item. {@code body} is ready to send once {@code note} is filled in; when the decision
     * needs two people, {@code secondOperator} is true, and {@code notBy} names the operator who may not make it.
     */
    public record Action(String label, String method, String path, Map<String, Object> body, boolean secondOperator,
                         String notBy) {
    }

    public record Link(String label, String path) {
    }

    /**
     * @param money      the payment's amount for a review case, the break's amount for a break
     * @param heldInSuspense money sitting in bank_suspense until this item is explained
     * @param suggestion for a credit nobody could match: the payout it most likely belongs to, and why
     */
    public record Item(Kind kind, UUID id, String title, Owner owner, String currency, BigDecimal money, BigDecimal heldInSuspense,
                       Instant openedAt, Instant dueAt, boolean overdue, long ageSeconds, String why, String nextStep,
                       String suggestion, UUID paymentIntentId, UUID settlementBatchId, List<Action> actions, List<Link> evidence) {
    }

    /**
     * @param items          everything waiting, before {@code owner} and {@code limit} are applied
     * @param overdue        of those, how many are past their due time
     * @param byOwner        how many wait for each owner
     * @param moneyInvolved  per currency, the money on the waiting items
     */
    public record Summary(long items, long overdue, Map<Owner, Long> byOwner, Map<String, BigDecimal> moneyInvolved) {
    }

    /** @param next the {@code after} value for the next page, or null on the last page */
    public record Worklist(Instant asOf, Summary summary, List<Item> items, String next) {
    }

    private final ReviewWorkflowService reviewWorkflowService;
    private final ReviewCaseRepository reviewCaseRepository;
    private final ReconciliationBreakRepository breakRepository;
    private final PaymentIntentRepository paymentIntentRepository;
    private final SettlementBatchRepository settlementBatchRepository;
    private final BankStatementLineRepository lineRepository;
    private final EntityManager entityManager;
    private final Clock clock;

    public WorklistService(ReviewWorkflowService reviewWorkflowService, ReviewCaseRepository reviewCaseRepository,
                           ReconciliationBreakRepository breakRepository, PaymentIntentRepository paymentIntentRepository,
                           SettlementBatchRepository settlementBatchRepository, BankStatementLineRepository lineRepository,
                           EntityManager entityManager, Clock clock) {
        this.reviewWorkflowService = reviewWorkflowService;
        this.reviewCaseRepository = reviewCaseRepository;
        this.breakRepository = breakRepository;
        this.paymentIntentRepository = paymentIntentRepository;
        this.settlementBatchRepository = settlementBatchRepository;
        this.lineRepository = lineRepository;
        this.entityManager = entityManager;
        this.clock = clock;
    }

    /**
     * One page of the worklist, most urgent first: ordered by due time, then id, and starting after {@code after}.
     * Reads one page from each source and merges them, so a large backlog costs no more than a small one; the summary
     * comes from counts and sums in the database.
     */
    @Transactional(readOnly = true)
    public Worklist worklist(Owner owner, PageCursor after, Integer limit) {
        int size = ListLimit.of(limit);
        Instant now = clock.instant();
        List<Item> candidates = new ArrayList<>();
        boolean more = false;

        if (owner == null || owner == Owner.OPERATIONS) {
            Specification<ReviewCase> unresolved = (root, query, cb) -> root.get("status").in(ReviewStatus.UNRESOLVED);
            PageSlice<ReviewCase> cases = Keyset.page(reviewCaseRepository, unresolved, "dueAt", true, after, size,
                    reviewCase -> new PageCursor(reviewCase.getDueAt(), reviewCase.getId()));
            more = cases.next() != null;
            List<ReviewCaseView> views = reviewWorkflowService.describe(cases.items());
            Map<UUID, PaymentIntent> payments = paymentIntentRepository.findAllById(views.stream().map(ReviewCaseView::paymentIntentId)
                    .filter(Objects::nonNull).distinct().toList()).stream().collect(Collectors.toMap(PaymentIntent::getId, Function.identity()));
            for (ReviewCaseView reviewCase : views) {
                candidates.add(reviewItem(reviewCase, payments.get(reviewCase.paymentIntentId()), now));
            }
        }
        if (owner != Owner.OPERATIONS) {
            Specification<ReconciliationBreak> open = (root, query, cb) -> cb.equal(root.get("status"), BreakStatus.OPEN);
            if (owner != null) {
                open = open.and((root, query, cb) -> cb.equal(root.get("owner"), BreakOwner.valueOf(owner.name())));
            }
            PageSlice<ReconciliationBreak> breaks = Keyset.page(breakRepository, open, "dueAt", true, after, size,
                    item -> new PageCursor(item.getDueAt(), item.getId()));
            more |= breaks.next() != null;
            List<BreakView> views = breaks.items().stream().map(item -> BreakView.of(item, now)).toList();
            List<SettlementBatch> landable = views.stream().anyMatch(b -> b.allowedActions().contains(BreakAction.ASSIGN_TO_BATCH))
                    ? settlementBatchRepository.findByPayoutStatusInOrderByCreatedAtAsc(EnumSet.of(PayoutStatus.IN_TRANSIT, PayoutStatus.BROKEN))
                    : List.of();
            for (BreakView view : views) {
                candidates.add(breakItem(view, landable, now));
            }
        }

        // The same order the database uses for each source: due time, then the id compared as unsigned bytes.
        candidates.sort(Comparator.comparing(Item::dueAt).thenComparing(Item::id, WorklistService::compareUnsigned));
        more |= candidates.size() > size;
        List<Item> shown = List.copyOf(candidates.subList(0, Math.min(size, candidates.size())));
        PageCursor next = more && !shown.isEmpty() ? new PageCursor(shown.getLast().dueAt(), shown.getLast().id()) : null;
        return new Worklist(now, summary(now), shown, next == null ? null : next.encode());
    }

    private Summary summary(Instant now) {
        long reviews = entityManager.createQuery("select count(r) from ReviewCase r where r.status in :unresolved", Long.class)
                .setParameter("unresolved", ReviewStatus.UNRESOLVED).getSingleResult();
        long overdue = entityManager.createQuery("select count(r) from ReviewCase r where r.status in :unresolved and r.dueAt < :now",
                Long.class).setParameter("unresolved", ReviewStatus.UNRESOLVED).setParameter("now", now).getSingleResult()
                + entityManager.createQuery("select count(b) from ReconciliationBreak b where b.status = :open and b.dueAt < :now",
                Long.class).setParameter("open", BreakStatus.OPEN).setParameter("now", now).getSingleResult();
        Map<Owner, Long> byOwner = new LinkedHashMap<>();
        byOwner.put(Owner.OPERATIONS, reviews);
        byOwner.put(Owner.TREASURY, 0L);
        byOwner.put(Owner.PROVIDER_OPERATIONS, 0L);
        for (Object[] row : entityManager.createQuery("select b.owner, count(b) from ReconciliationBreak b where b.status = :open group by b.owner",
                Object[].class).setParameter("open", BreakStatus.OPEN).getResultList()) {
            byOwner.put(Owner.valueOf(((BreakOwner) row[0]).name()), (Long) row[1]);
        }
        Map<String, BigDecimal> money = new TreeMap<>();
        entityManager.createQuery("""
                        select p.currency, sum(p.amount) from ReviewCase r, PaymentIntent p
                        where r.status in :unresolved and p.id = r.paymentIntentId group by p.currency""", Object[].class)
                .setParameter("unresolved", ReviewStatus.UNRESOLVED).getResultList()
                .forEach(row -> money.merge((String) row[0], (BigDecimal) row[1], BigDecimal::add));
        entityManager.createQuery("""
                        select b.currency, sum(b.amount) from ReconciliationBreak b
                        where b.status = :open and b.amount is not null group by b.currency""", Object[].class)
                .setParameter("open", BreakStatus.OPEN).getResultList()
                .forEach(row -> money.merge((String) row[0], (BigDecimal) row[1], BigDecimal::add));
        money.replaceAll((currency, amount) -> Money.atScale(amount, currency));
        long items = byOwner.values().stream().mapToLong(Long::longValue).sum();
        return new Summary(items, overdue, byOwner, money);
    }

    /** UUIDs compared byte by byte, unsigned: the order PostgreSQL and H2 sort them in. */
    static int compareUnsigned(UUID a, UUID b) {
        int high = Long.compareUnsigned(a.getMostSignificantBits(), b.getMostSignificantBits());
        return high != 0 ? high : Long.compareUnsigned(a.getLeastSignificantBits(), b.getLeastSignificantBits());
    }

    // ---- review cases and proposals ---------------------------------------------------------------

    private Item reviewItem(ReviewCaseView reviewCase, PaymentIntent payment, Instant now) {
        String path = "/api/review-cases/" + reviewCase.id();
        List<Link> evidence = new ArrayList<>(List.of(new Link("Review case", path)));
        if (reviewCase.paymentIntentId() != null) {
            evidence.add(new Link("Payment case file", "/api/payment-intents/" + reviewCase.paymentIntentId() + "/case-file"));
        }
        String currency = payment == null ? null : payment.getCurrency();
        BigDecimal money = payment == null ? null : payment.getAmount();
        String about = payment == null ? "" : " on a payment of " + money.toPlainString() + " " + currency;

        if (reviewCase.status() == ReviewStatus.AWAITING_APPROVAL) {
            Instant due = reviewCase.dueAt();
            String proposed = reviewCase.proposedAction() == null ? "a decision" : reviewCase.proposedAction().name();
            List<Action> actions = List.of(
                    new Action("Approve " + proposed, "POST", path + "/approve", Map.of("note", ""), true, reviewCase.proposedBy()),
                    new Action("Reject the proposal", "POST", path + "/reject-proposal", Map.of("note", ""), true, reviewCase.proposedBy()));
            return new Item(Kind.APPROVAL, reviewCase.id(), "Approve " + proposed + about, Owner.OPERATIONS, currency, money, null,
                    reviewCase.proposedAt() == null ? reviewCase.createdAt() : reviewCase.proposedAt(), due, now.isAfter(due),
                    Duration.between(reviewCase.createdAt(), now).toSeconds(), reviewCase.details(),
                    reviewCase.proposedBy() + " proposed " + proposed + (reviewCase.proposalNote() == null ? "" : ": \"" + reviewCase.proposalNote() + "\"")
                            + ". Any other operator approves it (the money moves) or rejects it (the case reopens).",
                    null, reviewCase.paymentIntentId(), null, actions, evidence);
        }

        Instant due = reviewCase.dueAt();
        List<Action> actions = reviewCase.allowedActions().stream()
                .map(action -> new Action(label(action), "POST", path + "/resolve", body("action", action.name(), "note", ""),
                        reviewCase.approvalRequiredFor().contains(action), null))
                .toList();
        String nextStep = actions.isEmpty()
                ? "No decision is possible yet; the case file says what the payment is waiting for."
                : "Decide: " + reviewCase.allowedActions().stream().map(ReviewAction::name).collect(Collectors.joining(", "))
                        + (reviewCase.approvalRequiredFor().isEmpty() ? "." : ". "
                        + reviewCase.approvalRequiredFor().stream().map(ReviewAction::name).collect(Collectors.joining(", "))
                        + " needs a second operator.");
        return new Item(Kind.REVIEW_CASE, reviewCase.id(), humanize(reviewCase.reason().name()) + about, Owner.OPERATIONS, currency,
                money, null, reviewCase.createdAt(), due, now.isAfter(due), Duration.between(reviewCase.createdAt(), now).toSeconds(),
                reviewCase.details(), nextStep, null, reviewCase.paymentIntentId(), null, actions, evidence);
    }

    // ---- reconciliation breaks --------------------------------------------------------------------

    private Item breakItem(BreakView open, List<SettlementBatch> landable, Instant now) {
        String path = "/api/reconciliation/breaks/" + open.id();
        List<Link> evidence = new ArrayList<>(List.of(new Link("Break", path)));
        if (open.settlementBatchId() != null) {
            evidence.add(new Link("Payout seen three ways", "/api/reconciliation/three-way/batches/" + open.settlementBatchId()));
        }
        if (open.statementId() != null) {
            evidence.add(new Link("Bank statement lines", "/api/bank/lines?statementId=" + open.statementId()));
        }
        String suggestion = null;
        List<Action> actions = new ArrayList<>();
        for (BreakAction action : open.allowedActions()) {
            if (action == BreakAction.ASSIGN_TO_BATCH) {
                List<SettlementBatch> candidates = candidates(open, landable);
                SettlementBatch only = candidates.size() == 1 ? candidates.getFirst() : null;
                if (only != null) {
                    suggestion = "Payout " + only.getPayoutReference() + " expects exactly this amount (net "
                            + only.getExpectedNetAmount().toPlainString() + " " + only.getCurrency() + ") and is "
                            + (only.getPayoutStatus() == PayoutStatus.BROKEN ? "missing its credit" : "still in transit")
                            + ". Confirm with the bank before assigning; the amount is checked again.";
                } else if (candidates.size() > 1) {
                    suggestion = candidates.size() + " payouts expect this amount: "
                            + candidates.stream().map(SettlementBatch::getPayoutReference).collect(Collectors.joining(", "))
                            + ". Only the bank can say which one.";
                }
                actions.add(new Action(only == null ? "Assign to a payout" : "Assign to " + only.getPayoutReference(), "POST",
                        path + "/resolve", body("action", action.name(), "batchId", only == null ? null : only.getId(), "note", ""),
                        false, null));
            } else {
                actions.add(new Action("Acknowledge", "POST", path + "/resolve", body("action", action.name(), "note", ""), false, null));
            }
        }
        BigDecimal suspense = open.suspenseAmount() == null || open.suspenseAmount().signum() == 0 ? null : open.suspenseAmount().abs();
        return new Item(Kind.BREAK, open.id(), humanize(open.category().name()) + (open.amount() == null ? ""
                : " of " + open.amount().toPlainString() + " " + open.currency()), Owner.valueOf(open.owner().name()), open.currency(),
                open.amount(), suspense, open.openedAt(), open.dueAt(), open.overdue(), open.ageSeconds(), open.detail(),
                nextStep(open.category()), suggestion, null, open.settlementBatchId(), actions, evidence);
    }

    /** Payouts this unmatched or late credit could land: in transit or missing their credit, with exactly its net. */
    private List<SettlementBatch> candidates(BreakView open, List<SettlementBatch> landable) {
        if (open.bankLineId() == null) {
            return List.of();
        }
        BankStatementLine line = lineRepository.findById(open.bankLineId()).orElse(null);
        if (line == null || !line.isCredit()) {
            return List.of();
        }
        BigDecimal arrived = line.getAmount().add(line.chargesOrZero());
        return landable.stream()
                .filter(batch -> batch.getPayoutStatus() == PayoutStatus.IN_TRANSIT
                        || (batch.getPayoutStatus() == PayoutStatus.BROKEN && batch.getBrokenReason() == BrokenReason.MISSING_CREDIT))
                .filter(batch -> batch.getCurrency().equals(line.getCurrency()) && batch.getExpectedNetAmount() != null
                        && batch.getExpectedNetAmount().compareTo(arrived) == 0)
                .toList();
    }

    private static String nextStep(BreakCategory category) {
        return switch (category) {
            case MISSING_CREDIT -> "Ask the provider for proof of the transfer and the bank for a trace. It closes itself when the credit arrives.";
            case RETURNED_PAYOUT -> "Ask the provider to send the payout again; acknowledge once it is agreed.";
            case UNMATCHED_CREDIT -> "Ask the bank whose transfer this is, then assign it to its payout.";
            case LATE_CREDIT -> "Confirm with the bank that this is the payout's transfer, then assign it.";
            case DUPLICATE_CREDIT -> "Ask the bank to take the second credit back. It closes itself when the return arrives.";
            case AMOUNT_MISMATCH -> "Ask the provider why the amount differs; the money stays in suspense until it is explained.";
            case UNMATCHED_DEBIT -> "Ask the bank what this debit is.";
            case STATEMENT_CONFLICT -> "Ask the bank which statement is authoritative; acknowledge once it is settled.";
            case PROVIDER_REFUSED_PAYOUT -> "Find out why the provider refused the payout before it is sent again.";
            case PROVIDER_PAYOUT_MISMATCH, MISSING_FROM_PROVIDER_REPORT, UNKNOWN_PROVIDER_PAYOUT ->
                    "Ask provider operations to correct their report; acknowledge once it agrees.";
        };
    }

    private static Map<String, Object> body(Object... pairs) {
        Map<String, Object> body = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            if (pairs[i + 1] != null) {
                body.put((String) pairs[i], pairs[i + 1]);
            }
        }
        return body;
    }

    private static String label(ReviewAction action) {
        return humanize(action.name());
    }

    private static String humanize(String constant) {
        String words = constant.toLowerCase(Locale.ROOT).replace('_', ' ');
        return Character.toUpperCase(words.charAt(0)) + words.substring(1);
    }

    static Owner owner(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Owner.valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "owner must be one of OPERATIONS, TREASURY, PROVIDER_OPERATIONS.");
        }
    }
}
