package id.fatarc.portfolio.payflowreliability.payment;

import id.fatarc.portfolio.payflowreliability.audit.AggregateType;
import id.fatarc.portfolio.payflowreliability.audit.AuditService;
import id.fatarc.portfolio.payflowreliability.common.BusinessException;
import id.fatarc.portfolio.payflowreliability.common.DatabaseErrors;
import id.fatarc.portfolio.payflowreliability.common.ErrorCode;
import id.fatarc.portfolio.payflowreliability.payment.dto.CreatePaymentIntentRequest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.UUID;

@Service
public class PaymentIntentService {
    private static final String MERCHANT_API = "merchant-api";
    private static final int MAX_IDEMPOTENCY_KEY_LENGTH = 100;

    private final PaymentIntentRepository paymentIntentRepository;
    private final AuditService auditService;
    private final TransactionTemplate transactionTemplate;

    public PaymentIntentService(PaymentIntentRepository paymentIntentRepository,
                                AuditService auditService,
                                PlatformTransactionManager transactionManager) {
        this.paymentIntentRepository = paymentIntentRepository;
        this.auditService = auditService;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    /**
     * Creates a payment intent, or replays the one already created with this merchant's
     * idempotency key. Keys are scoped per merchant, so two merchants never collide.
     *
     * <p>Two concurrent requests with the same key can both miss the lookup; the unique
     * constraint on (merchant, key) lets only one insert win. The loser retries once in a
     * fresh transaction, finds the winner's row, and replays it, so the client sees the same
     * result as a sequential retry instead of a 500.
     */
    public PaymentIntentCreation create(CreatePaymentIntentRequest request, String idempotencyKey) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "Idempotency-Key header is required.");
        }
        if (idempotencyKey.length() > MAX_IDEMPOTENCY_KEY_LENGTH) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                    "Idempotency-Key must be at most " + MAX_IDEMPOTENCY_KEY_LENGTH + " characters.");
        }

        try {
            return transactionTemplate.execute(status -> createOrReplay(request, idempotencyKey));
        } catch (DataIntegrityViolationException ex) {
            if (!DatabaseErrors.isUniqueViolation(ex)) {
                throw ex;
            }
            return transactionTemplate.execute(status -> createOrReplay(request, idempotencyKey));
        }
    }

    /** Starting an already-started payment returns it unchanged, so a retried start is safe. */
    @Transactional
    public PaymentIntent start(UUID paymentIntentId) {
        PaymentIntent intent = lockRequired(paymentIntentId);
        if (intent.getState() == PaymentState.PENDING) {
            return intent;
        }
        PaymentState previousState = intent.start();
        auditService.recordStateChange(intent.getId(), previousState, intent.getState(), MERCHANT_API,
                "Payment execution started.", "merchantReference=" + intent.getMerchantReference());
        return intent;
    }

    @Transactional(readOnly = true)
    public PaymentIntent getRequired(UUID paymentIntentId) {
        return paymentIntentRepository.findById(paymentIntentId)
                .orElseThrow(() -> notFound(paymentIntentId));
    }

    /** Loads a payment and locks its row until the caller's transaction ends. */
    @Transactional(propagation = Propagation.MANDATORY)
    public PaymentIntent lockRequired(UUID paymentIntentId) {
        return paymentIntentRepository.findForUpdate(paymentIntentId)
                .orElseThrow(() -> notFound(paymentIntentId));
    }

    private PaymentIntentCreation createOrReplay(CreatePaymentIntentRequest request, String idempotencyKey) {
        return paymentIntentRepository.findByMerchantIdAndIdempotencyKey(request.merchantId(), idempotencyKey)
                .map(existing -> new PaymentIntentCreation(replay(existing, request, idempotencyKey), true))
                .orElseGet(() -> {
                    PaymentIntent created = paymentIntentRepository.saveAndFlush(new PaymentIntent(
                            request.merchantId(),
                            request.merchantReference(),
                            request.amount(),
                            request.currency(),
                            idempotencyKey
                    ));
                    auditService.record(AggregateType.PAYMENT_INTENT, created.getId().toString(), created.getId(),
                            "PAYMENT_INTENT_CREATED", MERCHANT_API,
                            "Payment intent created for merchant reference " + request.merchantReference(),
                            "idempotencyKey=" + idempotencyKey);
                    return new PaymentIntentCreation(created, false);
                });
    }

    private PaymentIntent replay(PaymentIntent intent, CreatePaymentIntentRequest request, String idempotencyKey) {
        if (!intent.matchesRequest(request.merchantReference(), request.amount(), request.currency())) {
            throw new BusinessException(ErrorCode.IDEMPOTENCY_KEY_REUSED,
                    "Idempotency key " + idempotencyKey + " was already used for a different request payload.");
        }
        auditService.record(AggregateType.PAYMENT_INTENT, intent.getId().toString(), intent.getId(),
                "IDEMPOTENCY_REPLAYED", MERCHANT_API,
                "Existing payment intent returned for a repeated request.",
                "idempotencyKey=" + idempotencyKey);
        return intent;
    }

    private static BusinessException notFound(UUID paymentIntentId) {
        return new BusinessException(ErrorCode.PAYMENT_INTENT_NOT_FOUND, "Payment intent not found: " + paymentIntentId);
    }
}
