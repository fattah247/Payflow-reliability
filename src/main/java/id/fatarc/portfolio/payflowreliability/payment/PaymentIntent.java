package id.fatarc.portfolio.payflowreliability.payment;

import id.fatarc.portfolio.payflowreliability.common.InvalidStateTransitionException;
import id.fatarc.portfolio.payflowreliability.common.Money;
import jakarta.persistence.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.EnumSet;
import java.util.Set;
import java.util.UUID;

import static id.fatarc.portfolio.payflowreliability.payment.PaymentState.*;

@Entity
@Table(name = "payment_intents")
public class PaymentIntent {
    /** States a payment can be put on hold from; matches the documented state machine. */
    private static final Set<PaymentState> HOLDABLE = EnumSet.of(CREATED, PENDING, AUTHORIZED, SUCCESS, SETTLED);
    /** States in which the provider has already captured the money. */
    private static final Set<PaymentState> CAPTURED = EnumSet.of(SUCCESS, SETTLED, RECONCILED, REFUND_REQUESTED, REFUNDED);
    private static final Set<PaymentState> PRE_CAPTURE = EnumSet.of(CREATED, PENDING, AUTHORIZED);
    private static final Set<PaymentState> REVERSIBLE = EnumSet.of(AUTHORIZED, SUCCESS, SETTLED);

    @Id
    private UUID id;

    /** Optimistic lock: concurrent writers that read the same version cannot both commit. */
    @Version
    private Long version;

    @Column(nullable = false)
    private String merchantId;

    @Column(nullable = false)
    private String merchantReference;

    @Column(nullable = false, precision = 19, scale = 2)
    private BigDecimal amount;

    @Column(nullable = false, length = 3)
    private String currency;

    @Column(nullable = false)
    private String idempotencyKey;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private PaymentState state;

    /** While in MANUAL_REVIEW: the state the payment was held from, so an operator can release it. */
    @Enumerated(EnumType.STRING)
    private PaymentState stateBeforeReview;

    @Column(nullable = false)
    private Instant createdAt;

    @Column(nullable = false)
    private Instant updatedAt;

    protected PaymentIntent() {
    }

    public PaymentIntent(String merchantId, String merchantReference, BigDecimal amount, String currency, String idempotencyKey) {
        this.id = UUID.randomUUID();
        this.merchantId = merchantId;
        this.merchantReference = merchantReference;
        this.amount = Money.normalize(amount);
        this.currency = currency;
        this.idempotencyKey = idempotencyKey;
        this.state = CREATED;
        this.createdAt = Instant.now();
        this.updatedAt = this.createdAt;
    }

    public PaymentState start() {
        return transitionTo(PENDING, "Only a newly created intent can be started.", CREATED);
    }

    public PaymentState authorize() {
        return transitionTo(AUTHORIZED, "Only a pending payment can be authorized.", PENDING);
    }

    public PaymentState succeed() {
        return transitionTo(SUCCESS, "Only a pending or authorized payment can succeed.", PENDING, AUTHORIZED);
    }

    public PaymentState fail() {
        return transitionTo(FAILED, "Only a pending or authorized payment can fail.", PENDING, AUTHORIZED);
    }

    public PaymentState expire() {
        return transitionTo(EXPIRED, "Only a created, pending, or authorized payment can expire.",
                CREATED, PENDING, AUTHORIZED);
    }

    public PaymentState requireReversal() {
        return transitionTo(REVERSAL_REQUIRED, "Only an authorized or successful payment can require reversal.",
                AUTHORIZED, SUCCESS);
    }

    public PaymentState settle() {
        return transitionTo(SETTLED, "Only a successful payment can be settled.", SUCCESS);
    }

    public PaymentState reconcile() {
        return transitionTo(RECONCILED, "Only a settled payment can be reconciled.", SETTLED);
    }

    public PaymentState requestRefund() {
        return transitionTo(REFUND_REQUESTED, "Only successful or settled payments can request a refund.",
                SUCCESS, SETTLED);
    }

    /** A refund on a reconciled payment, allowed only after an operator approved it in manual review. */
    public PaymentState requestReviewedRefund() {
        return transitionTo(REFUND_REQUESTED, "Only a reconciled payment needs a reviewed refund.", RECONCILED);
    }

    public PaymentState refund() {
        return transitionTo(REFUNDED, "Refund must be requested first.", REFUND_REQUESTED);
    }

    public boolean canBeHeldForReview() {
        return HOLDABLE.contains(state);
    }

    /** Puts an in-flight payment on hold. Terminal outcomes (failed, refunded, ...) are facts and are never overwritten. */
    public PaymentState holdForReview() {
        if (!canBeHeldForReview()) {
            throw new InvalidStateTransitionException(
                    "Only an in-flight payment can be held for review. Current state: " + state);
        }
        PaymentState previousState = state;
        stateBeforeReview = previousState;
        state = MANUAL_REVIEW;
        updatedAt = Instant.now();
        return previousState;
    }

    /** Operator decision: the discrepancy was benign, continue from where the payment was held. */
    public PaymentState releaseFromReview() {
        requireOnHold();
        return leaveReview(stateBeforeReview);
    }

    /** Operator decision: no money was captured and the payment must not proceed. */
    public PaymentState failFromReview() {
        requireOnHold();
        if (!PRE_CAPTURE.contains(stateBeforeReview)) {
            throw new InvalidStateTransitionException(
                    "A payment held after capture cannot be failed; require a reversal instead. Held from: " + stateBeforeReview);
        }
        return leaveReview(FAILED);
    }

    /** Operator decision: money was authorized or captured and must be returned through a reversal. */
    public PaymentState reverseFromReview() {
        requireOnHold();
        if (!REVERSIBLE.contains(stateBeforeReview)) {
            throw new InvalidStateTransitionException(
                    "Nothing was authorized or captured, so there is nothing to reverse. Held from: " + stateBeforeReview);
        }
        return leaveReview(REVERSAL_REQUIRED);
    }

    public boolean isOnHold() {
        return state == MANUAL_REVIEW;
    }

    public boolean hasCaptured() {
        return CAPTURED.contains(state);
    }

    public boolean canFailFromReview() {
        return isOnHold() && PRE_CAPTURE.contains(stateBeforeReview);
    }

    public boolean canReverseFromReview() {
        return isOnHold() && REVERSIBLE.contains(stateBeforeReview);
    }

    public boolean matchesRequest(String merchantReference, BigDecimal amount, String currency) {
        return this.merchantReference.equals(merchantReference)
                && this.amount.compareTo(amount) == 0
                && this.currency.equals(currency);
    }

    private void requireOnHold() {
        if (state != MANUAL_REVIEW) {
            throw new InvalidStateTransitionException("Payment is not on hold for review. Current state: " + state);
        }
    }

    private PaymentState leaveReview(PaymentState nextState) {
        PaymentState previousState = state;
        state = nextState;
        stateBeforeReview = null;
        updatedAt = Instant.now();
        return previousState;
    }

    private PaymentState transitionTo(PaymentState nextState, String message, PaymentState... allowedStates) {
        for (PaymentState allowedState : allowedStates) {
            if (state == allowedState) {
                PaymentState previousState = state;
                state = nextState;
                updatedAt = Instant.now();
                return previousState;
            }
        }
        throw new InvalidStateTransitionException(message + " Current state: " + state);
    }

    public UUID getId() { return id; }
    public Long getVersion() { return version; }
    public String getMerchantId() { return merchantId; }
    public String getMerchantReference() { return merchantReference; }
    public BigDecimal getAmount() { return amount; }
    public String getCurrency() { return currency; }
    public String getIdempotencyKey() { return idempotencyKey; }
    public PaymentState getState() { return state; }
    public PaymentState getStateBeforeReview() { return stateBeforeReview; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
