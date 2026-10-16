package id.fatarc.portfolio.payflowreliability.payment;

import id.fatarc.portfolio.payflowreliability.common.InvalidStateTransitionException;
import id.fatarc.portfolio.payflowreliability.common.Money;
import id.fatarc.portfolio.payflowreliability.idempotency.RequestFingerprint;
import jakarta.persistence.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static id.fatarc.portfolio.payflowreliability.payment.PaymentState.*;

@Entity
@Table(name = "payment_intents")
public class PaymentIntent {
    /** States a payment can be put on hold from; final outcomes are facts and are never held. */
    private static final Set<PaymentState> HOLDABLE = EnumSet.of(CREATED, PENDING, AUTHORIZED, SUCCESS, SETTLED, RECONCILED);
    private static final Set<PaymentState> PRE_CAPTURE = EnumSet.of(CREATED, PENDING, AUTHORIZED);
    private static final Set<PaymentState> REVERSIBLE = EnumSet.of(AUTHORIZED, SUCCESS, SETTLED, RECONCILED);
    private static final Set<PaymentState> REFUNDABLE = EnumSet.of(SUCCESS, SETTLED, RECONCILED);

    @Id
    private UUID id;

    /** Optimistic lock: concurrent writers that read the same version cannot both commit. */
    @Version
    private Long version;

    @Column(nullable = false)
    private String merchantId;

    @Column(nullable = false)
    private String merchantReference;

    @Column(nullable = false, precision = 19, scale = 4)
    private BigDecimal amount;

    @Column(nullable = false, length = 3)
    private String currency;

    @Column(nullable = false)
    private String idempotencyKey;

    /** Canonical hash of the creating request; a retry under the same key must match it (see RequestFingerprint). */
    @Column(nullable = false, length = 64)
    private String requestFingerprint;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private PaymentState state;

    /** While in MANUAL_REVIEW: the state the payment was held from, so an operator can release it. */
    @Enumerated(EnumType.STRING)
    private PaymentState stateBeforeReview;

    /** When the payment was handed to the provider (start). The checkout confirmation SLO is measured from here. */
    private Instant startedAt;

    /** When the provider captured the money. Set once; drives the ledger capture journal. */
    private Instant capturedAt;

    private Instant cancelledAt;

    @Enumerated(EnumType.STRING)
    private CancellationReason cancellationReason;

    /** Sum of refunds the provider confirmed. */
    @Column(nullable = false, precision = 19, scale = 4)
    private BigDecimal refundedAmount;

    /** Captured money returned by a confirmed reversal. */
    @Column(nullable = false, precision = 19, scale = 4)
    private BigDecimal reversedAmount;

    /** Last time the status sweeper asked the provider about this payment. */
    private Instant lastProviderCheckAt;

    /**
     * When a row of the provider's settlement report matched this payment. One of the two pieces of evidence
     * RECONCILED needs; the other is a bank credit that landed the batch paying it out.
     */
    private Instant providerReportMatchedAt;

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
        this.amount = Money.normalize(amount, currency);
        this.currency = currency;
        this.idempotencyKey = idempotencyKey;
        this.requestFingerprint = fingerprintOf(merchantReference, amount, currency);
        this.state = CREATED;
        this.refundedAmount = Money.zero(currency);
        this.reversedAmount = Money.zero(currency);
        this.createdAt = Instant.now();
        this.updatedAt = this.createdAt;
    }

    public PaymentState start() {
        return start(Instant.now());
    }

    public PaymentState start(Instant now) {
        PaymentState previousState = transitionTo(PENDING, "Only a newly created intent can be started.", CREATED);
        this.startedAt = now;
        return previousState;
    }

    /**
     * Withdraws a payment the customer has not paid. Anything authorized or captured has to be returned through
     * the provider (a reversal) instead, so only CREATED and PENDING can be cancelled.
     */
    public PaymentState cancel(CancellationReason reason, Instant now) {
        PaymentState previousState = transitionTo(CANCELLED, "Only a payment the customer has not paid can be cancelled.",
                CREATED, PENDING);
        this.cancellationReason = reason;
        this.cancelledAt = now;
        return previousState;
    }

    public PaymentState authorize() {
        return transitionTo(AUTHORIZED, "Only a pending payment can be authorized.", PENDING);
    }

    public PaymentState succeed(Instant capturedAt) {
        PaymentState previousState = transitionTo(SUCCESS, "Only a pending or authorized payment can succeed.", PENDING, AUTHORIZED);
        this.capturedAt = capturedAt;
        return previousState;
    }

    public PaymentState fail() {
        return transitionTo(FAILED, "Only a pending or authorized payment can fail.", PENDING, AUTHORIZED);
    }

    public PaymentState expire() {
        return transitionTo(EXPIRED, "Only a created, pending, or authorized payment can expire.",
                CREATED, PENDING, AUTHORIZED);
    }

    /** Provider-requested reversal before payout. After payout it is a contradiction and goes to review. */
    public PaymentState requireReversal() {
        return transitionTo(REVERSAL_REQUIRED, "Only an authorized or successful payment can require reversal.",
                AUTHORIZED, SUCCESS);
    }

    /** The provider confirmed the reversal; any captured money not already refunded went back. */
    public PaymentState markReversed() {
        return markReversed(null);
    }

    /**
     * The provider confirmed the reversal and reports how much it returned. That can be less than what is
     * unrefunded here: a refund the provider already applied may still be on its way to us, and it is
     * recorded when it arrives. More than that, or a negative amount, contradicts our records.
     *
     * @param returnedByProvider the amount the provider reports, or null when it does not say
     */
    public PaymentState markReversed(BigDecimal returnedByProvider) {
        BigDecimal unrefunded = isCaptured() ? amount.subtract(refundedAmount) : Money.zero(currency);
        BigDecimal returned = returnedByProvider == null ? unrefunded : returnedByProvider;
        if (returned.signum() < 0 || returned.compareTo(unrefunded) > 0) {
            throw new IllegalStateException("Provider reports reversing " + returned.toPlainString() + " " + currency
                    + " of payment " + id + ", but only " + unrefunded.toPlainString() + " is unrefunded here.");
        }
        PaymentState previousState = transitionTo(REVERSED, "Only a payment awaiting reversal can be marked reversed.",
                REVERSAL_REQUIRED);
        this.reversedAmount = Money.normalize(returned, currency);
        return previousState;
    }

    public PaymentState settle() {
        return transitionTo(SETTLED, "Only a successful payment can be settled.", SUCCESS);
    }

    public PaymentState reconcile() {
        return transitionTo(RECONCILED, "Only a settled payment can be reconciled.", SETTLED);
    }

    public boolean canRequestRefund() {
        return REFUNDABLE.contains(state) && refundedAmount.compareTo(amount) < 0;
    }

    /**
     * Records a refund the provider confirmed. Money already moved, so this never fails on lifecycle
     * state; a full refund of a captured, unheld payment makes it REFUNDED.
     *
     * @return the previous state if the state changed, otherwise null
     */
    public PaymentState recordRefund(BigDecimal refunded) {
        BigDecimal total = refundedAmount.add(refunded);
        if (total.add(reversedAmount).compareTo(amount) > 0) {
            throw new IllegalStateException("Refunds would exceed the payment amount for " + id);
        }
        refundedAmount = Money.normalize(total, currency);
        updatedAt = Instant.now();
        if (refundedAmount.compareTo(amount) == 0 && REFUNDABLE.contains(state)) {
            PaymentState previousState = state;
            state = REFUNDED;
            return previousState;
        }
        return null;
    }

    public boolean canBeHeldForReview() {
        return HOLDABLE.contains(state);
    }

    /** Puts a payment on hold. Terminal outcomes (failed, refunded, reversed, ...) are facts and are never overwritten. */
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

    public void markProviderChecked(Instant checkedAt) {
        this.lastProviderCheckAt = checkedAt;
    }

    public void markProviderReportMatched(Instant matchedAt) {
        if (providerReportMatchedAt == null) {
            providerReportMatchedAt = matchedAt;
        }
    }

    public boolean isOnHold() {
        return state == MANUAL_REVIEW;
    }

    public boolean isCaptured() {
        return capturedAt != null;
    }

    public boolean canFailFromReview() {
        return isOnHold() && PRE_CAPTURE.contains(stateBeforeReview);
    }

    public boolean canReverseFromReview() {
        return isOnHold() && REVERSIBLE.contains(stateBeforeReview);
    }

    /**
     * The fingerprint of a payment creation request. Only business fields count: a retry may carry other
     * per-attempt data (headers, request ids) and still be the same request.
     */
    public static String fingerprintOf(String merchantReference, BigDecimal amount, String currency) {
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("merchantReference", merchantReference);
        fields.put("amount", amount);
        fields.put("currency", currency);
        return RequestFingerprint.of("payment-intents.create", fields);
    }

    /** Whether a retry under this payment's idempotency key is the same request that created it. */
    public boolean matchesFingerprint(String fingerprint) {
        return requestFingerprint.equals(fingerprint);
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
    public BigDecimal getAmount() { return Money.atScale(amount, currency); }
    public String getCurrency() { return currency; }
    public String getIdempotencyKey() { return idempotencyKey; }
    public String getRequestFingerprint() { return requestFingerprint; }
    public PaymentState getState() { return state; }
    public PaymentState getStateBeforeReview() { return stateBeforeReview; }
    public Instant getStartedAt() { return startedAt; }
    public Instant getCapturedAt() { return capturedAt; }
    public Instant getCancelledAt() { return cancelledAt; }
    public CancellationReason getCancellationReason() { return cancellationReason; }
    public BigDecimal getRefundedAmount() { return Money.atScale(refundedAmount, currency); }
    public BigDecimal getReversedAmount() { return Money.atScale(reversedAmount, currency); }
    public Instant getLastProviderCheckAt() { return lastProviderCheckAt; }
    public Instant getProviderReportMatchedAt() { return providerReportMatchedAt; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
