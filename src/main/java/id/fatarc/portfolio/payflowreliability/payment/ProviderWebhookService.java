package id.fatarc.portfolio.payflowreliability.payment;

import id.fatarc.portfolio.payflowreliability.audit.AuditService;
import id.fatarc.portfolio.payflowreliability.common.BusinessException;
import id.fatarc.portfolio.payflowreliability.payment.dto.ProviderWebhookRequest;
import id.fatarc.portfolio.payflowreliability.review.ReviewReason;
import id.fatarc.portfolio.payflowreliability.review.ReviewService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ProviderWebhookService {
    private static final String PROVIDER_ACTOR = "fake-provider";
    private static final String PROVIDER_EVENT = "PROVIDER_EVENT";
    private static final String PROVIDER_TRANSACTION = "PROVIDER_TRANSACTION";
    private static final String PAYMENT_INTENT = "PAYMENT_INTENT";

    private final String demoSignature;
    private final PaymentIntentRepository paymentIntentRepository;
    private final PaymentTransactionRepository paymentTransactionRepository;
    private final ReviewService reviewService;
    private final AuditService auditService;

    public ProviderWebhookService(
            @Value("${payment-lab.provider.demo-signature}") String providerSignature,
            PaymentIntentRepository paymentIntentRepository,
            PaymentTransactionRepository paymentTransactionRepository,
            ReviewService reviewService,
            AuditService auditService
    ) {
        this.demoSignature = providerSignature;
        this.paymentIntentRepository = paymentIntentRepository;
        this.paymentTransactionRepository = paymentTransactionRepository;
        this.reviewService = reviewService;
        this.auditService = auditService;
    }

    @Transactional
    public PaymentIntent process(ProviderWebhookRequest webhook, String signature) {
        if (!demoSignature.equals(signature)) {
            reviewService.openCase(
                    ReviewReason.INVALID_WEBHOOK_SIGNATURE,
                    PROVIDER_EVENT,
                    webhook.providerEventId(),
                    "Webhook rejected because provider signature is invalid.",
                    PROVIDER_ACTOR
            );
            auditService.record("FAKE_PROVIDER", webhook.providerEventId(), "WEBHOOK_RECEIVED",
                    PROVIDER_ACTOR, "Webhook rejected because provider signature is invalid.", "status=REJECTED");
            throw new BusinessException("Invalid provider signature.");
        }

        auditService.record("FAKE_PROVIDER", webhook.providerEventId(), "WEBHOOK_RECEIVED",
                PROVIDER_ACTOR, "Webhook accepted for processing.", "status=" + webhook.status());

        PaymentIntent intent = paymentIntentRepository.findById(webhook.paymentIntentId())
                .orElseThrow(() -> {
                    reviewService.openCase(
                            ReviewReason.PAYMENT_INTENT_NOT_FOUND,
                            PROVIDER_EVENT,
                            webhook.providerEventId(),
                            "Webhook points to a payment intent that does not exist locally.",
                            PROVIDER_ACTOR
                    );
                    return new BusinessException("Payment intent not found: " + webhook.paymentIntentId());
                });

        if (paymentTransactionRepository.findByProviderEventId(webhook.providerEventId()).isPresent()) {
            reviewService.openCase(
                    ReviewReason.DUPLICATE_PROVIDER_EVENT,
                    PROVIDER_EVENT,
                    webhook.providerEventId(),
                    "Duplicate fake provider event received.",
                    PROVIDER_ACTOR
            );
            auditService.record("FAKE_PROVIDER", webhook.providerEventId(), "DUPLICATE_WEBHOOK_IGNORED",
                    PROVIDER_ACTOR, "Duplicate fake provider event ignored.", "providerTransactionId=" + webhook.providerTransactionId());
            return intent;
        }

        if (paymentTransactionRepository.findByProviderTransactionId(webhook.providerTransactionId()).isPresent()) {
            reviewService.openCase(
                    ReviewReason.DUPLICATE_PROVIDER_EVENT,
                    PROVIDER_TRANSACTION,
                    webhook.providerTransactionId(),
                    "Duplicate fake provider transaction received with a new event identifier.",
                    PROVIDER_ACTOR
            );
            auditService.record("FAKE_PROVIDER", webhook.providerEventId(), "DUPLICATE_TRANSACTION_IGNORED",
                    PROVIDER_ACTOR, "Duplicate fake provider transaction ignored.", "providerTransactionId=" + webhook.providerTransactionId());
            return intent;
        }

        if (intent.getAmount().compareTo(webhook.amount()) != 0 || !intent.getCurrency().equals(webhook.currency())) {
            PaymentState previousState = intent.moveToManualReview();
            reviewService.openCase(
                    ReviewReason.AMOUNT_MISMATCH,
                    PAYMENT_INTENT,
                    intent.getId().toString(),
                    "Webhook amount or currency does not match local payment intent.",
                    PROVIDER_ACTOR
            );
            auditService.recordStateChange(PAYMENT_INTENT, intent.getId().toString(), previousState.name(),
                    intent.getState().name(), PROVIDER_ACTOR,
                    "Amount or currency mismatch found during webhook processing.",
                    "providerTransactionId=" + webhook.providerTransactionId());
            return intent;
        }

        try {
            applyWebhookStatus(intent, webhook);
        } catch (IllegalStateException ex) {
            reviewService.openCase(
                    ReviewReason.INVALID_STATE_TRANSITION,
                    PAYMENT_INTENT,
                    intent.getId().toString(),
                    ex.getMessage(),
                    PROVIDER_ACTOR
            );
            auditService.record(PAYMENT_INTENT, intent.getId().toString(), "WEBHOOK_IGNORED",
                    PROVIDER_ACTOR, ex.getMessage(), "status=" + webhook.status());
            return intent;
        }

        paymentTransactionRepository.save(new PaymentTransaction(
                intent.getId(),
                intent.getMerchantId(),
                webhook.providerEventId(),
                webhook.providerTransactionId(),
                webhook.status(),
                webhook.amount(),
                webhook.currency()
        ));

        auditService.record(PAYMENT_INTENT, intent.getId().toString(), "PROVIDER_WEBHOOK_PROCESSED",
                PROVIDER_ACTOR, "Fake provider webhook processed.", "status=" + webhook.status());

        return intent;
    }

    private void applyWebhookStatus(PaymentIntent intent, ProviderWebhookRequest webhook) {
        PaymentState previousState;
        switch (webhook.status()) {
            case AUTHORIZED -> {
                previousState = intent.authorize();
                auditService.recordStateChange(PAYMENT_INTENT, intent.getId().toString(), previousState.name(),
                        intent.getState().name(), PROVIDER_ACTOR,
                        "Provider authorized the payment.",
                        "providerTransactionId=" + webhook.providerTransactionId());
            }
            case SUCCESS -> {
                previousState = intent.succeed();
                auditService.recordStateChange(PAYMENT_INTENT, intent.getId().toString(), previousState.name(),
                        intent.getState().name(), PROVIDER_ACTOR,
                        "Provider marked the payment as successful.",
                        "providerTransactionId=" + webhook.providerTransactionId());
            }
            case FAILED -> {
                previousState = intent.fail();
                auditService.recordStateChange(PAYMENT_INTENT, intent.getId().toString(), previousState.name(),
                        intent.getState().name(), PROVIDER_ACTOR,
                        "Provider marked the payment as failed.",
                        "providerTransactionId=" + webhook.providerTransactionId());
            }
            case EXPIRED -> {
                previousState = intent.expire();
                auditService.recordStateChange(PAYMENT_INTENT, intent.getId().toString(), previousState.name(),
                        intent.getState().name(), PROVIDER_ACTOR,
                        "Provider marked the payment as expired.",
                        "providerTransactionId=" + webhook.providerTransactionId());
            }
            case REVERSAL_REQUIRED -> {
                previousState = intent.requireReversal();
                auditService.recordStateChange(PAYMENT_INTENT, intent.getId().toString(), previousState.name(),
                        intent.getState().name(), PROVIDER_ACTOR,
                        "Provider requested a reversal.",
                        "providerTransactionId=" + webhook.providerTransactionId());
            }
        }
    }
}
