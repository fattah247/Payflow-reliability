package id.fatarc.portfolio.payflowreliability.payment;

import id.fatarc.portfolio.payflowreliability.audit.AggregateType;
import id.fatarc.portfolio.payflowreliability.audit.AuditService;
import id.fatarc.portfolio.payflowreliability.common.BusinessException;
import id.fatarc.portfolio.payflowreliability.common.ErrorCode;
import id.fatarc.portfolio.payflowreliability.common.InvalidStateTransitionException;
import id.fatarc.portfolio.payflowreliability.payment.dto.ProviderWebhookRequest;
import id.fatarc.portfolio.payflowreliability.review.ReviewCase;
import id.fatarc.portfolio.payflowreliability.review.ReviewReason;
import id.fatarc.portfolio.payflowreliability.review.ReviewService;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Optional;
import java.util.UUID;

/**
 * Processes provider webhooks. Every delivery gets exactly one explicit {@link WebhookOutcome},
 * and expected outcomes (duplicates, mismatches, contradictions) are returned rather than thrown,
 * so the review cases and audit events they produce are committed.
 *
 * <p>Decision order:
 * <ol>
 *   <li>Signature: unauthenticated requests are rejected before anything is written.</li>
 *   <li>Lock the payment row, so deliveries for one payment are processed one at a time.</li>
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
 */
@Service
public class ProviderWebhookService {
    private static final Logger log = LoggerFactory.getLogger(ProviderWebhookService.class);
    private static final String PROVIDER_ACTOR = "fake-provider";

    private final byte[] expectedSignature;
    private final PaymentIntentRepository paymentIntentRepository;
    private final PaymentTransactionRepository paymentTransactionRepository;
    private final ProviderEventRepository providerEventRepository;
    private final ReviewService reviewService;
    private final AuditService auditService;
    private final MeterRegistry meterRegistry;

    public ProviderWebhookService(
            @Value("${payment-lab.provider.demo-signature}") String providerSignature,
            PaymentIntentRepository paymentIntentRepository,
            PaymentTransactionRepository paymentTransactionRepository,
            ProviderEventRepository providerEventRepository,
            ReviewService reviewService,
            AuditService auditService,
            MeterRegistry meterRegistry
    ) {
        this.expectedSignature = providerSignature.getBytes(StandardCharsets.UTF_8);
        this.paymentIntentRepository = paymentIntentRepository;
        this.paymentTransactionRepository = paymentTransactionRepository;
        this.providerEventRepository = providerEventRepository;
        this.reviewService = reviewService;
        this.auditService = auditService;
        this.meterRegistry = meterRegistry;
    }

    @Transactional
    public WebhookResult process(ProviderWebhookRequest webhook, String signature) {
        verifySignature(webhook, signature);

        Optional<PaymentIntent> intentLookup = paymentIntentRepository.findForUpdate(webhook.paymentIntentId());

        Optional<ProviderEvent> previousDelivery = providerEventRepository.findByProviderEventId(webhook.providerEventId());
        if (previousDelivery.isPresent()) {
            return redelivered(previousDelivery.get(), webhook, intentLookup.orElse(null));
        }

        if (intentLookup.isEmpty()) {
            return sendToReview(webhook, null, ReviewReason.PAYMENT_INTENT_NOT_FOUND,
                    AggregateType.PROVIDER_EVENT, webhook.providerEventId(),
                    "Webhook references payment intent " + webhook.paymentIntentId() + ", which does not exist locally.");
        }
        PaymentIntent intent = intentLookup.get();

        if (intent.isOnHold()) {
            return deferred(webhook, intent);
        }

        if (!intent.getCurrency().equals(webhook.currency())) {
            return holdAndReview(webhook, intent, ReviewReason.CURRENCY_MISMATCH,
                    "Webhook currency " + webhook.currency() + " does not match payment currency " + intent.getCurrency() + ".");
        }
        if (intent.getAmount().compareTo(webhook.amount()) != 0) {
            return holdAndReview(webhook, intent, ReviewReason.AMOUNT_MISMATCH,
                    "Webhook amount " + webhook.amount().toPlainString() + " does not match payment amount "
                            + intent.getAmount().toPlainString() + ".");
        }

        Optional<PaymentTransaction> transactionLookup =
                paymentTransactionRepository.findByProviderTransactionId(webhook.providerTransactionId());
        if (transactionLookup.isPresent()) {
            PaymentTransaction transaction = transactionLookup.get();
            if (!transaction.getPaymentIntentId().equals(intent.getId())) {
                return sendToReview(webhook, intent, ReviewReason.PROVIDER_TRANSACTION_CONFLICT,
                        AggregateType.PROVIDER_TRANSACTION, webhook.providerTransactionId(),
                        "Provider transaction " + webhook.providerTransactionId() + " already belongs to payment "
                                + transaction.getPaymentIntentId() + ".");
            }
            if (transaction.getProviderStatus() == webhook.status()) {
                return ignore(webhook, intent, WebhookOutcome.DUPLICATE_IGNORED,
                        "Provider transaction already has status " + webhook.status()
                                + "; this event restates a known fact under a new event id.");
            }
        }

        if (webhook.status() == ProviderStatus.AUTHORIZED && intent.hasCaptured()) {
            return ignore(webhook, intent, WebhookOutcome.STALE_IGNORED,
                    "Authorization arrived after the payment reached " + intent.getState() + "; capture implies authorization.");
        }

        PaymentState previousState;
        try {
            previousState = applyProviderStatus(intent, webhook.status());
        } catch (InvalidStateTransitionException ex) {
            return holdAndReview(webhook, intent, ReviewReason.INVALID_STATE_TRANSITION,
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

        String detail = "Provider status " + webhook.status() + " moved the payment from " + previousState
                + " to " + intent.getState() + ".";
        auditService.recordStateChange(intent.getId(), previousState, intent.getState(), PROVIDER_ACTOR,
                describe(webhook.status()), metadata(webhook));
        recordEvent(webhook, WebhookOutcome.APPLIED, detail, null);
        return result(webhook, WebhookOutcome.APPLIED, detail, null, null, intent, null);
    }

    private void verifySignature(ProviderWebhookRequest webhook, String signature) {
        boolean valid = signature != null
                && MessageDigest.isEqual(expectedSignature, signature.getBytes(StandardCharsets.UTF_8));
        if (!valid) {
            // Nothing is persisted for unauthenticated requests: otherwise anyone could fill the review queue.
            meterRegistry.counter("payflow.webhook.rejected", "reason", "invalid_signature").increment();
            log.warn("Webhook rejected: invalid provider signature for providerEventId={}", webhook.providerEventId());
            throw new BusinessException(ErrorCode.INVALID_SIGNATURE, "Invalid provider signature.");
        }
    }

    private WebhookResult redelivered(ProviderEvent previous, ProviderWebhookRequest webhook, PaymentIntent intent) {
        UUID paymentIntentId = intent == null ? null : intent.getId();
        if (previous.hasSamePayload(webhook)) {
            String detail = "Event was already processed with outcome " + previous.getOutcome() + ".";
            auditService.record(AggregateType.PROVIDER_EVENT, webhook.providerEventId(), paymentIntentId,
                    "DUPLICATE_WEBHOOK_IGNORED", PROVIDER_ACTOR, detail, metadata(webhook));
            return result(webhook, WebhookOutcome.DUPLICATE_IGNORED, detail, null, previous.getReviewCaseId(),
                    intent, previous.getOutcome());
        }

        String detail = "Provider re-sent event " + webhook.providerEventId()
                + " with a different payload than the delivery recorded at " + previous.getReceivedAt() + ".";
        ReviewCase reviewCase = reviewService.openCase(ReviewReason.PROVIDER_EVENT_CONFLICT,
                AggregateType.PROVIDER_EVENT, webhook.providerEventId(), paymentIntentId, detail, PROVIDER_ACTOR);
        auditService.record(AggregateType.PROVIDER_EVENT, webhook.providerEventId(), paymentIntentId,
                "WEBHOOK_SENT_TO_REVIEW", PROVIDER_ACTOR, detail, metadata(webhook));
        return result(webhook, WebhookOutcome.SENT_TO_REVIEW, detail, ReviewReason.PROVIDER_EVENT_CONFLICT,
                reviewCase.getId(), intent, previous.getOutcome());
    }

    private WebhookResult deferred(ProviderWebhookRequest webhook, PaymentIntent intent) {
        String detail = "Payment " + intent.getId() + " is on hold for manual review. Redeliver this event after the review is resolved.";
        auditService.record(AggregateType.PROVIDER_EVENT, webhook.providerEventId(), intent.getId(),
                "WEBHOOK_DEFERRED", PROVIDER_ACTOR, detail, metadata(webhook));
        return result(webhook, WebhookOutcome.DEFERRED, detail, null, null, intent, null);
    }

    /** Holds an in-flight payment so nothing settles or reconciles until an operator decides; final outcomes stay as they are. */
    private WebhookResult holdAndReview(ProviderWebhookRequest webhook, PaymentIntent intent, ReviewReason reason, String detail) {
        if (intent.canBeHeldForReview()) {
            PaymentState previousState = intent.holdForReview();
            auditService.recordStateChange(intent.getId(), previousState, intent.getState(), PROVIDER_ACTOR,
                    detail + " Payment held for manual review.", metadata(webhook));
            detail = detail + " Payment held in MANUAL_REVIEW.";
        } else {
            detail = detail + " Payment left in " + intent.getState() + " because that outcome is already final.";
        }
        return sendToReview(webhook, intent, reason, AggregateType.PAYMENT_INTENT, intent.getId().toString(), detail);
    }

    private WebhookResult sendToReview(ProviderWebhookRequest webhook, PaymentIntent intent, ReviewReason reason,
                                       String referenceType, String referenceId, String detail) {
        UUID paymentIntentId = intent == null ? null : intent.getId();
        ReviewCase reviewCase = reviewService.openCase(reason, referenceType, referenceId, paymentIntentId, detail, PROVIDER_ACTOR);
        auditService.record(AggregateType.PROVIDER_EVENT, webhook.providerEventId(), paymentIntentId,
                "WEBHOOK_SENT_TO_REVIEW", PROVIDER_ACTOR, detail, metadata(webhook) + ",reason=" + reason);
        recordEvent(webhook, WebhookOutcome.SENT_TO_REVIEW, detail, reviewCase.getId());
        return result(webhook, WebhookOutcome.SENT_TO_REVIEW, detail, reason, reviewCase.getId(), intent, null);
    }

    private WebhookResult ignore(ProviderWebhookRequest webhook, PaymentIntent intent, WebhookOutcome outcome, String detail) {
        auditService.record(AggregateType.PROVIDER_EVENT, webhook.providerEventId(), intent.getId(),
                "WEBHOOK_IGNORED", PROVIDER_ACTOR, detail, metadata(webhook) + ",outcome=" + outcome);
        recordEvent(webhook, outcome, detail, null);
        return result(webhook, outcome, detail, null, null, intent, null);
    }

    private void recordEvent(ProviderWebhookRequest webhook, WebhookOutcome outcome, String detail, UUID reviewCaseId) {
        // Flush now so a concurrent duplicate surfaces as a unique violation here, not at commit.
        providerEventRepository.saveAndFlush(new ProviderEvent(webhook, outcome, detail, reviewCaseId));
    }

    private WebhookResult result(ProviderWebhookRequest webhook, WebhookOutcome outcome, String detail,
                                 ReviewReason reviewReason, UUID reviewCaseId, PaymentIntent intent,
                                 WebhookOutcome originalOutcome) {
        meterRegistry.counter("payflow.webhook.events", "outcome", outcome.name(),
                "reason", reviewReason == null ? "none" : reviewReason.name()).increment();
        log.info("Webhook providerEventId={} status={} paymentIntentId={} outcome={}{}",
                webhook.providerEventId(), webhook.status(), webhook.paymentIntentId(), outcome,
                reviewReason == null ? "" : " reviewReason=" + reviewReason);
        return new WebhookResult(outcome, detail, reviewReason, reviewCaseId, intent, originalOutcome);
    }

    private static PaymentState applyProviderStatus(PaymentIntent intent, ProviderStatus status) {
        return switch (status) {
            case AUTHORIZED -> intent.authorize();
            case SUCCESS -> intent.succeed();
            case FAILED -> intent.fail();
            case EXPIRED -> intent.expire();
            case REVERSAL_REQUIRED -> intent.requireReversal();
        };
    }

    private static String describe(ProviderStatus status) {
        return switch (status) {
            case AUTHORIZED -> "Provider authorized the payment.";
            case SUCCESS -> "Provider marked the payment as successful.";
            case FAILED -> "Provider marked the payment as failed.";
            case EXPIRED -> "Provider marked the payment as expired.";
            case REVERSAL_REQUIRED -> "Provider requested a reversal.";
        };
    }

    private static String metadata(ProviderWebhookRequest webhook) {
        return "providerEventId=" + webhook.providerEventId()
                + ",providerTransactionId=" + webhook.providerTransactionId()
                + ",status=" + webhook.status();
    }
}
