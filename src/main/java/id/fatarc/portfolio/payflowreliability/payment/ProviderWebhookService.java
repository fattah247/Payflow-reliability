package id.fatarc.portfolio.payflowreliability.payment;

import id.fatarc.portfolio.payflowreliability.audit.AggregateType;
import id.fatarc.portfolio.payflowreliability.audit.AuditService;
import id.fatarc.portfolio.payflowreliability.checkout.CheckoutMetrics;
import id.fatarc.portfolio.payflowreliability.common.InvalidStateTransitionException;
import id.fatarc.portfolio.payflowreliability.ledger.LedgerService;
import id.fatarc.portfolio.payflowreliability.outbox.OutboxService;
import id.fatarc.portfolio.payflowreliability.payment.dto.ProviderWebhookRequest;
import id.fatarc.portfolio.payflowreliability.review.ReviewCase;
import id.fatarc.portfolio.payflowreliability.review.ReviewReason;
import id.fatarc.portfolio.payflowreliability.review.ReviewService;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.Optional;
import java.util.UUID;

/**
 * Applies provider events, whether pushed as signed webhooks (signature verified by the controller
 * before this runs) or recovered by the status sweeper polling the provider. Every event gets exactly
 * one explicit {@link WebhookOutcome}, and expected outcomes (duplicates, mismatches, contradictions)
 * are returned rather than thrown, so the review cases and audit events they produce are committed.
 *
 * <p>Decision order:
 * <ol>
 *   <li>Lock the payment row, so events for one payment are processed one at a time.</li>
 *   <li>Inbox: a known event id is a redelivery (same payload: ignore; different payload: review).</li>
 *   <li>Unknown payment: review.</li>
 *   <li>Payment on hold: defer (409) so the provider redelivers after the review decision.</li>
 *   <li>Currency or amount mismatch: hold the payment (if still in flight) and review.</li>
 *   <li>Provider transaction owned by another payment: review.</li>
 *   <li>Same status already recorded for this provider transaction: duplicate fact, ignore.</li>
 *   <li>AUTHORIZED after capture: stale, ignore.</li>
 *   <li>Apply the transition. A contradiction (for example FAILED after SUCCESS) holds the payment
 *       (if still in flight) and goes to review, so no money moves while the provider and we disagree.</li>
 * </ol>
 * A capture posts its ledger journal in the same transaction; a provider-requested reversal is
 * queued for execution through the outbox.
 */
@Service
public class ProviderWebhookService {
    private static final Logger log = LoggerFactory.getLogger(ProviderWebhookService.class);
    private static final String PROVIDER_ACTOR = "provider";

    private final CheckoutMetrics checkoutMetrics;
    private final PaymentIntentRepository paymentIntentRepository;
    private final PaymentTransactionRepository paymentTransactionRepository;
    private final ProviderEventRepository providerEventRepository;
    private final ReviewService reviewService;
    private final AuditService auditService;
    private final LedgerService ledgerService;
    private final OutboxService outboxService;
    private final MeterRegistry meterRegistry;
    private final Clock clock;

    public ProviderWebhookService(
            PaymentIntentRepository paymentIntentRepository,
            PaymentTransactionRepository paymentTransactionRepository,
            ProviderEventRepository providerEventRepository,
            ReviewService reviewService,
            AuditService auditService,
            LedgerService ledgerService,
            OutboxService outboxService,
            CheckoutMetrics checkoutMetrics,
            MeterRegistry meterRegistry,
            Clock clock
    ) {
        this.checkoutMetrics = checkoutMetrics;
        this.paymentIntentRepository = paymentIntentRepository;
        this.paymentTransactionRepository = paymentTransactionRepository;
        this.providerEventRepository = providerEventRepository;
        this.reviewService = reviewService;
        this.auditService = auditService;
        this.ledgerService = ledgerService;
        this.outboxService = outboxService;
        this.meterRegistry = meterRegistry;
        this.clock = clock;
    }

    @Transactional
    public WebhookResult process(ProviderWebhookRequest webhook) {
        return process(webhook, EventSource.WEBHOOK);
    }

    @Transactional
    public WebhookResult process(ProviderWebhookRequest webhook, EventSource source) {
        Event event = new Event(webhook, source);
        Optional<PaymentIntent> intentLookup = paymentIntentRepository.findForUpdate(webhook.paymentIntentId());

        Optional<ProviderEvent> previousDelivery = providerEventRepository.findByProviderEventId(webhook.providerEventId());
        if (previousDelivery.isPresent()) {
            return redelivered(previousDelivery.get(), event, intentLookup.orElse(null));
        }

        if (intentLookup.isEmpty()) {
            return sendToReview(event, null, ReviewReason.PAYMENT_INTENT_NOT_FOUND,
                    AggregateType.PROVIDER_EVENT, webhook.providerEventId(),
                    "Event references payment intent " + webhook.paymentIntentId() + ", which does not exist locally.");
        }
        PaymentIntent intent = intentLookup.get();

        if (intent.isOnHold()) {
            return deferred(event, intent);
        }

        if (!intent.getCurrency().equals(webhook.currency())) {
            return holdAndReview(event, intent, ReviewReason.CURRENCY_MISMATCH,
                    "Event currency " + webhook.currency() + " does not match payment currency " + intent.getCurrency() + ".");
        }
        if (intent.getAmount().compareTo(webhook.amount()) != 0) {
            return holdAndReview(event, intent, ReviewReason.AMOUNT_MISMATCH,
                    "Event amount " + webhook.amount().toPlainString() + " does not match payment amount "
                            + intent.getAmount().toPlainString() + ".");
        }

        Optional<PaymentTransaction> transactionLookup =
                paymentTransactionRepository.findByProviderTransactionId(webhook.providerTransactionId());
        if (transactionLookup.isPresent()) {
            PaymentTransaction transaction = transactionLookup.get();
            if (!transaction.getPaymentIntentId().equals(intent.getId())) {
                return sendToReview(event, intent, ReviewReason.PROVIDER_TRANSACTION_CONFLICT,
                        AggregateType.PROVIDER_TRANSACTION, webhook.providerTransactionId(),
                        "Provider transaction " + webhook.providerTransactionId() + " already belongs to payment "
                                + transaction.getPaymentIntentId() + ".");
            }
            if (transaction.getProviderStatus() == webhook.status()) {
                return ignore(event, intent, WebhookOutcome.DUPLICATE_IGNORED,
                        "Provider transaction already has status " + webhook.status()
                                + "; this event restates a known fact under a new event id.");
            }
        }

        if (webhook.status() == ProviderStatus.AUTHORIZED && intent.isCaptured()) {
            return ignore(event, intent, WebhookOutcome.STALE_IGNORED,
                    "Authorization arrived after the payment reached " + intent.getState() + "; capture implies authorization.");
        }

        PaymentState previousState;
        try {
            previousState = applyProviderStatus(intent, webhook.status());
        } catch (InvalidStateTransitionException ex) {
            return holdAndReview(event, intent, ReviewReason.INVALID_STATE_TRANSITION,
                    "Provider status " + webhook.status() + " contradicts the payment: " + ex.getMessage());
        }

        transactionLookup.ifPresentOrElse(
                transaction -> transaction.recordProviderStatus(webhook.status()),
                () -> paymentTransactionRepository.save(new PaymentTransaction(
                        intent.getId(),
                        intent.getMerchantId(),
                        webhook.providerTransactionId(),
                        webhook.status(),
                        webhook.amount(),
                        webhook.currency()
                )));

        auditService.recordStateChange(intent.getId(), previousState, intent.getState(), PROVIDER_ACTOR,
                describe(webhook.status()), event.metadata());
        if (webhook.status() == ProviderStatus.SUCCESS) {
            ledgerService.capture(intent.getId(), intent.getMerchantId(), intent.getCurrency(), intent.getAmount());
            checkoutMetrics.confirmed(intent);
        }
        if (webhook.status() == ProviderStatus.REVERSAL_REQUIRED) {
            outboxService.enqueueReversal(intent.getId());
        }
        String detail = "Provider status " + webhook.status() + " moved the payment from " + previousState
                + " to " + intent.getState() + ".";
        recordEvent(event, WebhookOutcome.APPLIED, detail, null);
        return result(event, WebhookOutcome.APPLIED, detail, null, null, intent, null);
    }

    private WebhookResult redelivered(ProviderEvent previous, Event event, PaymentIntent intent) {
        UUID paymentIntentId = intent == null ? null : intent.getId();
        ProviderWebhookRequest webhook = event.webhook();
        if (previous.hasSamePayload(webhook)) {
            String detail = "Event was already processed with outcome " + previous.getOutcome() + ".";
            auditService.record(AggregateType.PROVIDER_EVENT, webhook.providerEventId(), paymentIntentId,
                    "DUPLICATE_WEBHOOK_IGNORED", PROVIDER_ACTOR, detail, event.metadata());
            return result(event, WebhookOutcome.DUPLICATE_IGNORED, detail, null, previous.getReviewCaseId(),
                    intent, previous.getOutcome());
        }

        String detail = "Provider re-sent event " + webhook.providerEventId()
                + " with a different payload than the delivery recorded at " + previous.getReceivedAt() + ".";
        ReviewCase reviewCase = reviewService.openCase(ReviewReason.PROVIDER_EVENT_CONFLICT,
                AggregateType.PROVIDER_EVENT, webhook.providerEventId(), paymentIntentId, detail, PROVIDER_ACTOR);
        auditService.record(AggregateType.PROVIDER_EVENT, webhook.providerEventId(), paymentIntentId,
                "WEBHOOK_SENT_TO_REVIEW", PROVIDER_ACTOR, detail, event.metadata());
        return result(event, WebhookOutcome.SENT_TO_REVIEW, detail, ReviewReason.PROVIDER_EVENT_CONFLICT,
                reviewCase.getId(), intent, previous.getOutcome());
    }

    private WebhookResult deferred(Event event, PaymentIntent intent) {
        String detail = "Payment " + intent.getId() + " is on hold for manual review. Redeliver this event after the review is resolved.";
        auditService.record(AggregateType.PROVIDER_EVENT, event.webhook().providerEventId(), intent.getId(),
                "WEBHOOK_DEFERRED", PROVIDER_ACTOR, detail, event.metadata());
        return result(event, WebhookOutcome.DEFERRED, detail, null, null, intent, null);
    }

    /** Holds an in-flight payment so nothing settles or reconciles until an operator decides; final outcomes stay as they are. */
    private WebhookResult holdAndReview(Event event, PaymentIntent intent, ReviewReason reason, String detail) {
        if (intent.canBeHeldForReview()) {
            PaymentState previousState = intent.holdForReview();
            auditService.recordStateChange(intent.getId(), previousState, intent.getState(), PROVIDER_ACTOR,
                    detail + " Payment held for manual review.", event.metadata());
            detail = detail + " Payment held in MANUAL_REVIEW.";
        } else {
            detail = detail + " Payment left in " + intent.getState() + " because that outcome is already final.";
        }
        return sendToReview(event, intent, reason, AggregateType.PAYMENT_INTENT, intent.getId().toString(), detail);
    }

    private WebhookResult sendToReview(Event event, PaymentIntent intent, ReviewReason reason,
                                       String referenceType, String referenceId, String detail) {
        UUID paymentIntentId = intent == null ? null : intent.getId();
        ReviewCase reviewCase = reviewService.openCase(reason, referenceType, referenceId, paymentIntentId, detail, PROVIDER_ACTOR);
        auditService.record(AggregateType.PROVIDER_EVENT, event.webhook().providerEventId(), paymentIntentId,
                "WEBHOOK_SENT_TO_REVIEW", PROVIDER_ACTOR, detail, event.metadata() + ",reason=" + reason);
        recordEvent(event, WebhookOutcome.SENT_TO_REVIEW, detail, reviewCase.getId());
        return result(event, WebhookOutcome.SENT_TO_REVIEW, detail, reason, reviewCase.getId(), intent, null);
    }

    private WebhookResult ignore(Event event, PaymentIntent intent, WebhookOutcome outcome, String detail) {
        auditService.record(AggregateType.PROVIDER_EVENT, event.webhook().providerEventId(), intent.getId(),
                "WEBHOOK_IGNORED", PROVIDER_ACTOR, detail, event.metadata() + ",outcome=" + outcome);
        recordEvent(event, outcome, detail, null);
        return result(event, outcome, detail, null, null, intent, null);
    }

    private void recordEvent(Event event, WebhookOutcome outcome, String detail, UUID reviewCaseId) {
        // Flush now so a concurrent duplicate surfaces as a unique violation here, not at commit.
        providerEventRepository.saveAndFlush(new ProviderEvent(event.webhook(), event.source(), outcome, detail, reviewCaseId));
    }

    private WebhookResult result(Event event, WebhookOutcome outcome, String detail,
                                 ReviewReason reviewReason, UUID reviewCaseId, PaymentIntent intent,
                                 WebhookOutcome originalOutcome) {
        ProviderWebhookRequest webhook = event.webhook();
        meterRegistry.counter("payflow.webhook.events", "outcome", outcome.name(), "source", event.source().name(),
                "reason", reviewReason == null ? "none" : reviewReason.name()).increment();
        log.info("Provider event providerEventId={} source={} status={} paymentIntentId={} outcome={}{}",
                webhook.providerEventId(), event.source(), webhook.status(), webhook.paymentIntentId(), outcome,
                reviewReason == null ? "" : " reviewReason=" + reviewReason);
        return new WebhookResult(outcome, detail, reviewReason, reviewCaseId, intent, originalOutcome);
    }

    private PaymentState applyProviderStatus(PaymentIntent intent, ProviderStatus status) {
        return switch (status) {
            case AUTHORIZED -> intent.authorize();
            case SUCCESS -> intent.succeed(clock.instant());
            case FAILED -> intent.fail();
            case EXPIRED -> intent.expire();
            case REVERSAL_REQUIRED -> intent.requireReversal();
        };
    }

    private static String describe(ProviderStatus status) {
        return switch (status) {
            case AUTHORIZED -> "Provider authorized the payment.";
            case SUCCESS -> "Provider captured the payment.";
            case FAILED -> "Provider marked the payment as failed.";
            case EXPIRED -> "Provider marked the payment as expired.";
            case REVERSAL_REQUIRED -> "Provider requested a reversal.";
        };
    }

    private record Event(ProviderWebhookRequest webhook, EventSource source) {
        String metadata() {
            return "providerEventId=" + webhook.providerEventId()
                    + ",providerTransactionId=" + webhook.providerTransactionId()
                    + ",status=" + webhook.status()
                    + ",source=" + source;
        }
    }
}
