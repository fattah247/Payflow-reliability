package id.fatarc.portfolio.payflowreliability.operations;

import id.fatarc.portfolio.payflowreliability.bank.BankStatementLine;
import id.fatarc.portfolio.payflowreliability.bank.BankStatementLineRepository;
import id.fatarc.portfolio.payflowreliability.common.BusinessException;
import id.fatarc.portfolio.payflowreliability.common.ErrorCode;
import id.fatarc.portfolio.payflowreliability.common.ListLimit;
import id.fatarc.portfolio.payflowreliability.payment.PaymentIntent;
import id.fatarc.portfolio.payflowreliability.payment.PaymentIntentRepository;
import id.fatarc.portfolio.payflowreliability.reconciliation.breaks.BreakAction;
import id.fatarc.portfolio.payflowreliability.reconciliation.breaks.BreakCategory;
import id.fatarc.portfolio.payflowreliability.reconciliation.breaks.BreakResolutionService;
import id.fatarc.portfolio.payflowreliability.reconciliation.breaks.BreakStatus;
import id.fatarc.portfolio.payflowreliability.reconciliation.breaks.BreakView;
import id.fatarc.portfolio.payflowreliability.review.ReviewAction;
import id.fatarc.portfolio.payflowreliability.review.ReviewReason;
import id.fatarc.portfolio.payflowreliability.review.ReviewStatus;
import id.fatarc.portfolio.payflowreliability.review.ReviewWorkflowService;
import id.fatarc.portfolio.payflowreliability.review.dto.ReviewCaseView;
import id.fatarc.portfolio.payflowreliability.settlement.BrokenReason;
import id.fatarc.portfolio.payflowreliability.settlement.PayoutStatus;
import id.fatarc.portfolio.payflowreliability.settlement.SettlementBatch;
import id.fatarc.portfolio.payflowreliability.settlement.SettlementBatchRepository;
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
 * <p>Due times follow the alerts that would page or ticket if the item waited longer: a review case within 4 hours
 * ({@code PayflowReviewQueueAging}), a provider call with an unknown outcome within 5 minutes
 * ({@code PayflowProviderCallOutcomeUnknown}), a proposal within 1 hour ({@code PayflowApprovalWaiting}), and a break
 * by its category's own due time ({@code PayflowReconciliationBreaksOverdue}).
 */
@Service
public class WorklistService {
    static final Duration REVIEW_DUE = Duration.ofHours(4);
    static final Duration UNKNOWN_OUTCOME_DUE = Duration.ofMinutes(5);
    static final Duration APPROVAL_DUE = Duration.ofHours(1);

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

    public record Worklist(Instant asOf, Summary summary, List<Item> items) {
    }

    private final ReviewWorkflowService reviewWorkflowService;
    private final BreakResolutionService breakResolutionService;
    private final PaymentIntentRepository paymentIntentRepository;
    private final SettlementBatchRepository settlementBatchRepository;
    private final BankStatementLineRepository lineRepository;
    private final Clock clock;

    public WorklistService(ReviewWorkflowService reviewWorkflowService, BreakResolutionService breakResolutionService,
                           PaymentIntentRepository paymentIntentRepository, SettlementBatchRepository settlementBatchRepository,
                           BankStatementLineRepository lineRepository, Clock clock) {
        this.reviewWorkflowService = reviewWorkflowService;
        this.breakResolutionService = breakResolutionService;
        this.paymentIntentRepository = paymentIntentRepository;
        this.settlementBatchRepository = settlementBatchRepository;
        this.lineRepository = lineRepository;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public Worklist worklist(Owner owner, Integer limit) {
        int max = ListLimit.of(limit);
        Instant now = clock.instant();
        List<Item> all = new ArrayList<>();

        List<ReviewCaseView> cases = reviewWorkflowService.list(ReviewStatus.UNRESOLVED, null, null);
        Map<UUID, PaymentIntent> payments = paymentIntentRepository.findAllById(cases.stream().map(ReviewCaseView::paymentIntentId)
                .filter(Objects::nonNull).distinct().toList()).stream().collect(Collectors.toMap(PaymentIntent::getId, Function.identity()));
        for (ReviewCaseView reviewCase : cases) {
            all.add(reviewItem(reviewCase, payments.get(reviewCase.paymentIntentId()), now));
        }

        List<BreakView> breaks = breakResolutionService.list(BreakStatus.OPEN, null, null);
        List<SettlementBatch> landable = breaks.stream().anyMatch(b -> b.allowedActions().contains(BreakAction.ASSIGN_TO_BATCH))
                ? settlementBatchRepository.findByPayoutStatusInOrderByCreatedAtAsc(EnumSet.of(PayoutStatus.IN_TRANSIT, PayoutStatus.BROKEN))
                : List.of();
        for (BreakView open : breaks) {
            all.add(breakItem(open, landable, now));
        }

        // Most urgent first: the earliest due (overdue items come first by construction), then the most money.
        all.sort(Comparator.comparing(Item::dueAt).thenComparing(item -> item.money() == null ? BigDecimal.ZERO : item.money(),
                Comparator.reverseOrder()));
        Map<Owner, Long> byOwner = new LinkedHashMap<>();
        for (Owner each : Owner.values()) {
            byOwner.put(each, all.stream().filter(item -> item.owner() == each).count());
        }
        Map<String, BigDecimal> money = new TreeMap<>();
        all.stream().filter(item -> item.money() != null && item.currency() != null)
                .forEach(item -> money.merge(item.currency(), item.money(), BigDecimal::add));
        Summary summary = new Summary(all.size(), all.stream().filter(Item::overdue).count(), byOwner, money);
        List<Item> shown = all.stream().filter(item -> owner == null || item.owner() == owner).limit(max).toList();
        return new Worklist(now, summary, shown);
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
            Instant due = (reviewCase.proposedAt() == null ? reviewCase.createdAt() : reviewCase.proposedAt()).plus(APPROVAL_DUE);
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

        Duration within = reviewCase.reason() == ReviewReason.PROVIDER_OUTCOME_UNKNOWN ? UNKNOWN_OUTCOME_DUE : REVIEW_DUE;
        Instant due = reviewCase.createdAt().plus(within);
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
