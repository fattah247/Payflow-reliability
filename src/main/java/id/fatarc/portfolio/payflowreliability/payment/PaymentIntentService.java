package id.fatarc.portfolio.payflowreliability.payment;

import id.fatarc.portfolio.payflowreliability.audit.AggregateType;
import id.fatarc.portfolio.payflowreliability.audit.AuditService;
import id.fatarc.portfolio.payflowreliability.checkout.CheckoutMetrics;
import id.fatarc.portfolio.payflowreliability.common.BusinessException;
import id.fatarc.portfolio.payflowreliability.common.DatabaseErrors;
import id.fatarc.portfolio.payflowreliability.common.ErrorCode;
import id.fatarc.portfolio.payflowreliability.common.Keyset;
import id.fatarc.portfolio.payflowreliability.common.PageCursor;
import id.fatarc.portfolio.payflowreliability.common.PageSlice;
import id.fatarc.portfolio.payflowreliability.merchant.MerchantService;
import id.fatarc.portfolio.payflowreliability.payment.dto.CreatePaymentIntentRequest;
import id.fatarc.portfolio.payflowreliability.risk.RequiredConfirmation;
import id.fatarc.portfolio.payflowreliability.risk.RiskService;
import id.fatarc.portfolio.payflowreliability.security.Caller;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.time.Clock;
import java.util.UUID;

@Service
public class PaymentIntentService {
    private final PaymentIntentRepository paymentIntentRepository;
    private final MerchantService merchantService;
    private final AuditService auditService;
    private final CheckoutMetrics checkoutMetrics;
    private final RiskService riskService;
    private final Clock clock;
    private final TransactionTemplate transactionTemplate;
    private final ApplicationEventPublisher events;

    public PaymentIntentService(PaymentIntentRepository paymentIntentRepository,
                                MerchantService merchantService,
                                AuditService auditService,
                                CheckoutMetrics checkoutMetrics,
                                RiskService riskService,
                                Clock clock,
                                PlatformTransactionManager transactionManager,
                                ApplicationEventPublisher events) {
        this.paymentIntentRepository = paymentIntentRepository;
        this.merchantService = merchantService;
        this.auditService = auditService;
        this.checkoutMetrics = checkoutMetrics;
        this.riskService = riskService;
        this.clock = clock;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        this.events = events;
    }

    /**
     * Creates a payment intent for the caller's merchant, or replays the one already created with
     * this idempotency key. Keys are scoped per merchant.
     *
     * <p>Two concurrent requests with the same key can both miss the lookup; the unique constraint
     * on (merchant, key) lets only one insert win. The loser retries once in a fresh transaction,
     * finds the winner's row, and replays it, so the client sees the same result as a sequential retry.
     */
    public PaymentIntentCreation create(CreatePaymentIntentRequest request, String idempotencyKey, Caller caller) {
        IdempotencyKeys.requireValid(idempotencyKey);
        if (!caller.isMerchant()) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "Only a merchant API key can create payments.");
        }
        if (request.merchantId() != null && !request.merchantId().equals(caller.merchantId())) {
            throw new BusinessException(ErrorCode.FORBIDDEN,
                    "merchantId " + request.merchantId() + " does not match the merchant of this API key.");
        }
        String merchantId = caller.merchantId();
        merchantService.requireActive(merchantId);

        try {
            return transactionTemplate.execute(status -> createOrReplay(request, merchantId, idempotencyKey, caller));
        } catch (DataIntegrityViolationException ex) {
            if (!DatabaseErrors.isUniqueViolation(ex)) {
                throw ex;
            }
            return transactionTemplate.execute(status -> createOrReplay(request, merchantId, idempotencyKey, caller));
        }
    }

    /**
     * Starting an already-started payment returns it unchanged, so a retried start is safe. A payment waiting for a
     * step-up or blocked by device risk is never started: that is what keeps it away from the provider.
     */
    @Transactional
    public PaymentIntent start(UUID paymentIntentId, Caller caller) {
        PaymentIntent intent = lockAccessible(paymentIntentId, caller);
        if (intent.getState() == PaymentState.PENDING) {
            return intent;
        }
        if (intent.getState() == PaymentState.REQUIRES_CONFIRMATION) {
            throw new BusinessException(ErrorCode.STEP_UP_REQUIRED,
                    "Payment " + paymentIntentId + " needs the customer's step-up confirmation before it can start.");
        }
        if (intent.getState() == PaymentState.BLOCKED) {
            throw new BusinessException(ErrorCode.PAYMENT_BLOCKED,
                    "Payment " + paymentIntentId + " was blocked by the device-risk policy and will not be sent to the provider.");
        }
        PaymentState previousState = intent.start(clock.instant());
        auditService.recordStateChange(intent.getId(), previousState, intent.getState(), caller.name(),
                "Payment execution started.", "merchantReference=" + intent.getMerchantReference());
        return intent;
    }

    /**
     * Withdraws a payment the customer has not paid. Cancelling a cancelled payment again returns it unchanged,
     * so a retried cancel is safe. An abandoned checkout is counted as money lost to the checkout experience.
     */
    @Transactional
    public PaymentIntent cancel(UUID paymentIntentId, CancellationReason reason, Caller caller) {
        PaymentIntent intent = lockAccessible(paymentIntentId, caller);
        if (intent.getState() == PaymentState.CANCELLED) {
            return intent;
        }
        boolean awaitingStepUp = intent.getState() == PaymentState.REQUIRES_CONFIRMATION;
        PaymentState previousState = intent.cancel(reason, clock.instant());
        if (awaitingStepUp) {
            riskService.onCancelledBeforeConfirmation(intent);
        }
        auditService.recordStateChange(intent.getId(), previousState, intent.getState(), caller.name(),
                "Payment cancelled before the customer paid.", "reason=" + reason);
        if (reason == CancellationReason.ABANDONED) {
            checkoutMetrics.abandoned(intent);
        }
        return intent;
    }

    /** The customer confirmed the device-risk step-up (the shop's backend verified the device's signature). */
    @Transactional
    public PaymentIntent confirm(UUID paymentIntentId, RequiredConfirmation method, Caller caller) {
        PaymentIntent intent = lockAccessible(paymentIntentId, caller);
        riskService.confirm(intent, method, caller.name());
        return intent;
    }

    @Transactional(readOnly = true)
    public PaymentIntent getRequired(UUID paymentIntentId) {
        return paymentIntentRepository.findById(paymentIntentId)
                .orElseThrow(() -> notFound(paymentIntentId));
    }

    /**
     * A merchant's payments for one of its own references, newest first, a page at a time. This is how a shop finds a payment
     * whose create response never reached it and whose idempotency key it lost. A merchant always searches its own
     * payments; an operator names the merchant.
     */
    @Transactional(readOnly = true)
    public PageSlice<PaymentIntent> findByReference(String merchantReference, String merchantId, Caller caller, PageCursor after,
                                                    Integer limit) {
        String merchant = caller.isMerchant() ? caller.merchantId() : merchantId;
        if (merchant == null || merchant.isBlank()) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "merchantId is required when an operator looks up a reference.");
        }
        if (merchantReference == null || merchantReference.isBlank()) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "merchantReference is required.");
        }
        Specification<PaymentIntent> filter = (root, query, cb) -> cb.and(cb.equal(root.get("merchantId"), merchant),
                cb.equal(root.get("merchantReference"), merchantReference));
        return Keyset.page(paymentIntentRepository, filter, "createdAt", false, after, limit,
                intent -> new PageCursor(intent.getCreatedAt(), intent.getId()));
    }

    /** A merchant asking for another merchant's payment gets the same 404 as for a missing one. */
    @Transactional(readOnly = true)
    public PaymentIntent getAccessible(UUID paymentIntentId, Caller caller) {
        PaymentIntent intent = getRequired(paymentIntentId);
        if (!caller.canAccessMerchant(intent.getMerchantId())) {
            throw notFound(paymentIntentId);
        }
        return intent;
    }

    /** Loads a payment and locks its row until the caller's transaction ends. */
    @Transactional(propagation = Propagation.MANDATORY)
    public PaymentIntent lockRequired(UUID paymentIntentId) {
        return paymentIntentRepository.findForUpdate(paymentIntentId)
                .orElseThrow(() -> notFound(paymentIntentId));
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public PaymentIntent lockAccessible(UUID paymentIntentId, Caller caller) {
        PaymentIntent intent = lockRequired(paymentIntentId);
        if (!caller.canAccessMerchant(intent.getMerchantId())) {
            throw notFound(paymentIntentId);
        }
        return intent;
    }

    private PaymentIntentCreation createOrReplay(CreatePaymentIntentRequest request, String merchantId,
                                                 String idempotencyKey, Caller caller) {
        return paymentIntentRepository.findForUpdateByMerchantIdAndIdempotencyKey(merchantId, idempotencyKey)
                .map(existing -> new PaymentIntentCreation(replay(existing, request, idempotencyKey, caller), true))
                .orElseGet(() -> {
                    PaymentIntent created = paymentIntentRepository.saveAndFlush(new PaymentIntent(
                            merchantId,
                            request.merchantReference(),
                            request.amount(),
                            request.currency(),
                            idempotencyKey,
                            request.action()
                    ));
                    auditService.record(AggregateType.PAYMENT_INTENT, created.getId().toString(), created.getId(),
                            "PAYMENT_INTENT_CREATED", caller.name(),
                            "Payment intent created for merchant reference " + request.merchantReference(),
                            "idempotencyKey=" + idempotencyKey);
                    events.publishEvent(new PaymentCreated(created.getId()));
                    // Decided once, on the first attempt: a retry is answered with this decision, whatever it carries.
                    riskService.decide(created, request.riskEvidence(), caller.name());
                    return new PaymentIntentCreation(created, false);
                });
    }

    private PaymentIntent replay(PaymentIntent intent, CreatePaymentIntentRequest request, String idempotencyKey,
                                 Caller caller) {
        if (!intent.matchesFingerprint(PaymentIntent.fingerprintOf(request.merchantReference(), request.amount(), request.currency(),
                request.action()))) {
            throw new BusinessException(ErrorCode.IDEMPOTENCY_KEY_REUSED,
                    "Idempotency key " + idempotencyKey + " was already used for a different request payload.");
        }
        checkoutMetrics.replayed("payment-intents.create");
        auditService.record(AggregateType.PAYMENT_INTENT, intent.getId().toString(), intent.getId(),
                "IDEMPOTENCY_REPLAYED", caller.name(),
                "Existing payment intent returned for a repeated request.",
                "idempotencyKey=" + idempotencyKey);
        return intent;
    }

    private static BusinessException notFound(UUID paymentIntentId) {
        return new BusinessException(ErrorCode.PAYMENT_INTENT_NOT_FOUND, "Payment intent not found: " + paymentIntentId);
    }
}
