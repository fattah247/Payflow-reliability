package id.fatarc.portfolio.payflowreliability.payment;

import id.fatarc.portfolio.payflowreliability.audit.AggregateType;
import id.fatarc.portfolio.payflowreliability.audit.AuditService;
import id.fatarc.portfolio.payflowreliability.common.BusinessException;
import id.fatarc.portfolio.payflowreliability.common.DatabaseErrors;
import id.fatarc.portfolio.payflowreliability.common.ErrorCode;
import id.fatarc.portfolio.payflowreliability.merchant.MerchantService;
import id.fatarc.portfolio.payflowreliability.payment.dto.CreatePaymentIntentRequest;
import id.fatarc.portfolio.payflowreliability.security.Caller;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.UUID;

@Service
public class PaymentIntentService {
    private final PaymentIntentRepository paymentIntentRepository;
    private final MerchantService merchantService;
    private final AuditService auditService;
    private final TransactionTemplate transactionTemplate;

    public PaymentIntentService(PaymentIntentRepository paymentIntentRepository,
                                MerchantService merchantService,
                                AuditService auditService,
                                PlatformTransactionManager transactionManager) {
        this.paymentIntentRepository = paymentIntentRepository;
        this.merchantService = merchantService;
        this.auditService = auditService;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
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

    /** Starting an already-started payment returns it unchanged, so a retried start is safe. */
    @Transactional
    public PaymentIntent start(UUID paymentIntentId, Caller caller) {
        PaymentIntent intent = lockAccessible(paymentIntentId, caller);
        if (intent.getState() == PaymentState.PENDING) {
            return intent;
        }
        PaymentState previousState = intent.start();
        auditService.recordStateChange(intent.getId(), previousState, intent.getState(), caller.name(),
                "Payment execution started.", "merchantReference=" + intent.getMerchantReference());
        return intent;
    }

    @Transactional(readOnly = true)
    public PaymentIntent getRequired(UUID paymentIntentId) {
        return paymentIntentRepository.findById(paymentIntentId)
                .orElseThrow(() -> notFound(paymentIntentId));
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
                            idempotencyKey
                    ));
                    auditService.record(AggregateType.PAYMENT_INTENT, created.getId().toString(), created.getId(),
                            "PAYMENT_INTENT_CREATED", caller.name(),
                            "Payment intent created for merchant reference " + request.merchantReference(),
                            "idempotencyKey=" + idempotencyKey);
                    return new PaymentIntentCreation(created, false);
                });
    }

    private PaymentIntent replay(PaymentIntent intent, CreatePaymentIntentRequest request, String idempotencyKey,
                                 Caller caller) {
        if (!intent.matchesFingerprint(PaymentIntent.fingerprintOf(request.merchantReference(), request.amount(), request.currency()))) {
            throw new BusinessException(ErrorCode.IDEMPOTENCY_KEY_REUSED,
                    "Idempotency key " + idempotencyKey + " was already used for a different request payload.");
        }
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
