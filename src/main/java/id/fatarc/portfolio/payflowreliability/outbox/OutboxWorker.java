package id.fatarc.portfolio.payflowreliability.outbox;

import id.fatarc.portfolio.payflowreliability.audit.AuditService;
import id.fatarc.portfolio.payflowreliability.payment.PaymentIntent;
import id.fatarc.portfolio.payflowreliability.payment.PaymentIntentRepository;
import id.fatarc.portfolio.payflowreliability.payment.PaymentState;
import id.fatarc.portfolio.payflowreliability.payment.PaymentTransaction;
import id.fatarc.portfolio.payflowreliability.payment.PaymentTransactionRepository;
import id.fatarc.portfolio.payflowreliability.ledger.LedgerService;
import id.fatarc.portfolio.payflowreliability.provider.PaymentProviderClient;
import id.fatarc.portfolio.payflowreliability.provider.ProviderProperties;
import id.fatarc.portfolio.payflowreliability.provider.ProviderResult;
import id.fatarc.portfolio.payflowreliability.refund.RefundRequest;
import id.fatarc.portfolio.payflowreliability.refund.RefundRequestRepository;
import id.fatarc.portfolio.payflowreliability.refund.RefundService;
import id.fatarc.portfolio.payflowreliability.refund.RefundStatus;
import id.fatarc.portfolio.payflowreliability.review.ReviewReason;
import id.fatarc.portfolio.payflowreliability.review.ReviewService;
import id.fatarc.portfolio.payflowreliability.settlement.PayoutService;
import id.fatarc.portfolio.payflowreliability.settlement.PayoutStatus;
import id.fatarc.portfolio.payflowreliability.settlement.SettlementBatch;
import id.fatarc.portfolio.payflowreliability.settlement.SettlementBatchRepository;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Executes outbox commands against the provider.
 *
 * <ul>
 *   <li>Claims each command with a lease (safe with several workers or instances; a crashed
 *       worker's lease expires and the command is picked up again).</li>
 *   <li>Marks the command dispatched, and commits that, before the call leaves: from then on the provider
 *       may have acted, so the command can no longer be cancelled and every later attempt first asks the
 *       provider what happened to the idempotency key.</li>
 *   <li>Calls the provider outside any database transaction, so no lock is held during network I/O, and
 *       always about the same provider transaction (pinned at first dispatch).</li>
 *   <li>Treats a refusal as final only when the provider says the operation failed, or when it refused a
 *       request it had never seen before. Everything else is an unknown outcome, retried with exponential
 *       backoff and jitter; when retries run out the command needs review (retrying is always safe).</li>
 *   <li>Records the result in one transaction with the domain change and the ledger journal, locking the
 *       payment first (the global lock order; a payout locks its settlement batch instead). If recording fails, that counts as an attempt too, so a
 *       result that cannot be recorded escalates instead of looping.</li>
 *   <li>Never resends under a key the provider may have forgotten: once the first dispatch is older than the
 *       provider's key retention (minus a margin), it only looks the key up, and escalates if the provider
 *       no longer knows it.</li>
 * </ul>
 *
 * The idempotency key is the command's, derived once from the business operation when the command was
 * created ({@code refund-<refundId>}, {@code reversal-<paymentId>}, {@code payout-<batchId>}), never per attempt: that is what lets a
 * worker that died after the provider acted be replaced by another that sends the very same request.
 */
@Component
public class OutboxWorker {
    private static final Logger log = LoggerFactory.getLogger(OutboxWorker.class);
    private static final String ACTOR = "system:outbox-worker";

    private final OutboxCommandRepository commandRepository;
    private final PaymentIntentRepository paymentIntentRepository;
    private final PaymentTransactionRepository transactionRepository;
    private final RefundRequestRepository refundRepository;
    private final RefundService refundService;
    private final SettlementBatchRepository batchRepository;
    private final PayoutService payoutService;
    private final LedgerService ledgerService;
    private final ReviewService reviewService;
    private final AuditService auditService;
    private final PaymentProviderClient providerClient;
    private final Duration keyResendWindow;
    private final MeterRegistry meterRegistry;
    private final Clock clock;
    private final TransactionTemplate transactions;
    private final String workerId = "worker-" + UUID.randomUUID().toString().substring(0, 8);
    private final int batchSize;
    private final int maxAttempts;
    private final Duration lease;
    private final Duration baseBackoff;
    private final Duration maxBackoff;
    private final Duration blockedRetry;

    public OutboxWorker(OutboxCommandRepository commandRepository,
                        PaymentIntentRepository paymentIntentRepository,
                        PaymentTransactionRepository transactionRepository,
                        RefundRequestRepository refundRepository,
                        RefundService refundService,
                        SettlementBatchRepository batchRepository,
                        PayoutService payoutService,
                        LedgerService ledgerService,
                        ReviewService reviewService,
                        AuditService auditService,
                        PaymentProviderClient providerClient,
                        ProviderProperties providerProperties,
                        MeterRegistry meterRegistry,
                        Clock clock,
                        PlatformTransactionManager transactionManager,
                        @Value("${payflow.outbox.batch-size:20}") int batchSize,
                        @Value("${payflow.outbox.max-attempts:8}") int maxAttempts,
                        @Value("${payflow.outbox.lease:30s}") Duration lease,
                        @Value("${payflow.outbox.base-backoff:2s}") Duration baseBackoff,
                        @Value("${payflow.outbox.max-backoff:5m}") Duration maxBackoff,
                        @Value("${payflow.outbox.blocked-retry:5m}") Duration blockedRetry) {
        this.commandRepository = commandRepository;
        this.paymentIntentRepository = paymentIntentRepository;
        this.transactionRepository = transactionRepository;
        this.refundRepository = refundRepository;
        this.refundService = refundService;
        this.batchRepository = batchRepository;
        this.payoutService = payoutService;
        this.ledgerService = ledgerService;
        this.reviewService = reviewService;
        this.auditService = auditService;
        this.providerClient = providerClient;
        this.keyResendWindow = providerProperties.keyResendWindow();
        this.meterRegistry = meterRegistry;
        this.clock = clock;
        this.transactions = new TransactionTemplate(transactionManager);
        this.batchSize = batchSize;
        this.maxAttempts = maxAttempts;
        this.lease = lease;
        this.baseBackoff = baseBackoff;
        this.maxBackoff = maxBackoff;
        this.blockedRetry = blockedRetry;
    }

    /** Processes due commands once; returns how many this worker claimed. */
    public int runOnce() {
        Instant now = clock.instant();
        List<UUID> due = transactions.execute(status -> commandRepository.findDue(now, PageRequest.of(0, batchSize)));
        int processed = 0;
        for (UUID id : due) {
            Instant claimTime = clock.instant();
            Integer claimed = transactions.execute(status -> commandRepository.claim(id, workerId, claimTime, claimTime.plus(lease)));
            if (claimed != null && claimed == 1) {
                processed++;
                process(id);
            }
        }
        return processed;
    }

    private void process(UUID id) {
        Call call;
        try {
            call = transactions.execute(status -> prepare(id));
        } catch (RuntimeException ex) {
            log.error("Outbox command {} could not be prepared", id, ex);
            afterFailure(id, "Could not prepare the provider call: " + ex.getMessage());
            return;
        }
        if (call == null) {
            return;
        }
        Attempt attempt = execute(call);
        ProviderResult result = attempt.result();
        try {
            transactions.executeWithoutResult(status -> record(call, attempt));
        } catch (RuntimeException ex) {
            log.error("Outbox command {}: provider answered {} but recording it failed", id, result, ex);
            afterFailure(id, "Provider answered " + result + " but recording it failed: " + ex.getMessage());
        }
    }

    /**
     * Loads what the call needs and marks the command dispatched, or settles the command without calling
     * when the call is no longer appropriate. Locks the payment first, like every other writer.
     */
    private Call prepare(UUID id) {
        Optional<OutboxCommandRepository.Subject> subject = commandRepository.findSubjectById(id);
        if (subject.isEmpty()) {
            return null;
        }
        if (subject.get().getCommandType() == CommandType.PAYOUT) {
            return preparePayout(id, subject.get().getSettlementBatchId());
        }
        PaymentIntent intent = paymentIntentRepository.findForUpdate(subject.get().getPaymentIntentId()).orElseThrow();
        OutboxCommand command = commandRepository.findById(id).orElse(null);
        if (command == null || command.getStatus() != CommandStatus.PENDING || !workerId.equals(command.getLeaseOwner())) {
            return null;
        }
        Instant now = clock.instant();
        boolean previouslyDispatched = command.wasDispatched();
        Optional<String> providerTransactionId = Optional.ofNullable(command.getProviderTransactionId())
                .or(() -> originalProviderTransaction(intent.getId()));

        RefundRequest refund = null;
        if (command.getCommandType() == CommandType.REFUND) {
            refund = refundRepository.findById(UUID.fromString(command.getReferenceId())).orElseThrow();
            if (refund.getStatus() != RefundStatus.REQUESTED) {
                command.cancel(now);
                return null;
            }
            if (!previouslyDispatched && EnumSet.of(PaymentState.REVERSAL_REQUIRED, PaymentState.REVERSED).contains(intent.getState())) {
                // The reversal returns whatever is unrefunded; this refund was never sent, so it is simply not needed.
                refundService.onPaymentReversedBeforeSending(refund, intent, ACTOR);
                command.fail("The payment was reversed before this refund was sent.", now);
                return null;
            }
            if (intent.isOnHold()) {
                command.retryLater("Waiting: payment " + intent.getId() + " is on hold for review.", now.plus(blockedRetry), now);
                return null;
            }
            var contradiction = reviewService.findOpenCase(intent.getId(), ReviewReason.INVALID_STATE_TRANSITION);
            if (contradiction.isPresent()) {
                // Money must not go back while the provider contradicts the payment; wait for the decision.
                command.retryLater("Waiting for review case " + contradiction.get().getId(), now.plus(blockedRetry), now);
                return null;
            }
        } else if (intent.getState() != PaymentState.REVERSAL_REQUIRED) {
            command.cancel(now);
            return null;
        }
        if (providerTransactionId.isEmpty()) {
            escalate(command, intent, "No provider transaction is recorded for the payment.", now);
            return null;
        }

        command.markDispatched(providerTransactionId.get(), now);
        boolean lookupOnly = previouslyDispatched && command.getDispatchedAt().isBefore(now.minus(keyResendWindow));
        return new Call(id, command.getCommandType(), intent.getId(), null, refund == null ? null : refund.getId(),
                command.getIdempotencyKey(), command.getProviderTransactionId(), null, intent.getMerchantId(),
                refund == null ? null : refund.getAmount(), intent.getCurrency(), List.of(), previouslyDispatched,
                lookupOnly, command.getDispatchedAt());
    }

    /** A payout is about a settlement batch: the batch row is locked first, then the command. */
    private Call preparePayout(UUID id, UUID batchId) {
        SettlementBatch batch = batchRepository.findForUpdate(batchId).orElseThrow();
        OutboxCommand command = commandRepository.findById(id).orElse(null);
        if (command == null || command.getStatus() != CommandStatus.PENDING || !workerId.equals(command.getLeaseOwner())) {
            return null;
        }
        Instant now = clock.instant();
        if (batch.getPayoutStatus() != PayoutStatus.SENT) {
            command.cancel(now);
            return null;
        }
        boolean previouslyDispatched = command.wasDispatched();
        command.markDispatched(null, now);
        boolean lookupOnly = previouslyDispatched && command.getDispatchedAt().isBefore(now.minus(keyResendWindow));
        return new Call(id, CommandType.PAYOUT, null, batch.getId(), null, command.getIdempotencyKey(), null,
                batch.getPayoutReference(), batch.getMerchantId(), batch.getTotalAmount(), batch.getCurrency(),
                payoutService.providerTransactionIds(batch), previouslyDispatched, lookupOnly, command.getDispatchedAt());
    }

    /** The transaction the payment was made with: the first one recorded. Later rows (a chargeback) are not it. */
    private Optional<String> originalProviderTransaction(UUID paymentIntentId) {
        return transactionRepository.findByPaymentIntentIdOrderByCreatedAtAsc(paymentIntentId).stream()
                .findFirst()
                .map(PaymentTransaction::getProviderTransactionId);
    }

    private Attempt execute(Call call) {
        try {
            boolean mayHaveBeenApplied = call.previouslyDispatched();
            if (call.previouslyDispatched()) {
                Optional<ProviderResult> earlier = switch (call.type()) {
                    case REFUND -> providerClient.findRefund(call.idempotencyKey());
                    case REVERSAL -> providerClient.findReversal(call.idempotencyKey());
                    case PAYOUT -> providerClient.findPayout(call.idempotencyKey());
                };
                if (earlier.isPresent()) {
                    if (!(earlier.get() instanceof ProviderResult.Unknown) || call.lookupOnly()) {
                        return new Attempt(earlier.get(), false);
                    }
                }
                if (call.lookupOnly()) {
                    return new Attempt(new ProviderResult.Unknown("First sent at " + call.dispatchedAt() + ", longer ago than the "
                            + "provider keeps idempotency keys; it no longer recognizes " + call.idempotencyKey()
                            + ". It may never have received the call, or it may have forgotten the key: a resend could be "
                            + "applied twice. Check the provider's records for " + call.subject() + "."),
                            true);
                }
                // Empty: the provider never received an earlier attempt, so this send is the first it sees.
                mayHaveBeenApplied = earlier.isPresent();
            }
            ProviderResult result = switch (call.type()) {
                case REFUND -> providerClient.refund(call.idempotencyKey(), call.providerTransactionId(), call.amount(),
                        call.currency(), call.refundId().toString());
                case REVERSAL -> providerClient.reverse(call.idempotencyKey(), call.providerTransactionId(),
                        call.paymentIntentId().toString());
                case PAYOUT -> providerClient.payout(call.idempotencyKey(), call.reference(), call.merchantId(),
                        call.amount(), call.currency(), call.providerTransactionIds());
            };
            if (mayHaveBeenApplied && result instanceof ProviderResult.Rejected rejected && !rejected.operationFailed()) {
                return new Attempt(new ProviderResult.Unknown("Provider refused the resent request (" + rejected.reason()
                        + "), but an earlier attempt may have been applied."), false);
            }
            return new Attempt(result, false);
        } catch (RuntimeException ex) {
            return new Attempt(new ProviderResult.Unknown("Provider call failed: " + ex.getMessage()), false);
        }
    }

    private void record(Call call, Attempt attempt) {
        ProviderResult result = attempt.result();
        Instant now = clock.instant();
        boolean payout = call.type() == CommandType.PAYOUT;
        SettlementBatch batch = payout ? batchRepository.findForUpdate(call.batchId()).orElseThrow() : null;
        PaymentIntent intent = payout ? null : paymentIntentRepository.findForUpdate(call.paymentIntentId()).orElseThrow();
        OutboxCommand command = commandRepository.findById(call.commandId()).orElseThrow();
        if (command.getStatus() != CommandStatus.PENDING || !workerId.equals(command.getLeaseOwner())) {
            // Another worker took over after the lease expired; it asks the provider before resending.
            log.warn("Outbox command {} lease lost before recording; the next owner will look the result up", command.getId());
            return;
        }
        command.recordAttempt(now);
        String resultName = switch (result) {
            case ProviderResult.PayoutAccepted accepted -> {
                if (!payout) {
                    throw new IllegalStateException("Provider answered a " + call.type() + " with a payout.");
                }
                command.succeed(accepted.providerPayoutId(), now);
                payoutService.onProviderAccepted(batch, accepted, ACTOR);
                yield "succeeded";
            }
            case ProviderResult.Succeeded succeeded -> {
                if (payout) {
                    throw new IllegalStateException("Provider confirmed payout " + call.reference()
                            + " without its fee, net amount and value date.");
                }
                command.succeed(succeeded.providerReference(), now);
                if (call.type() == CommandType.REFUND) {
                    RefundRequest refund = refundRepository.findById(call.refundId()).orElseThrow();
                    if (succeeded.amount() != null && succeeded.amount().compareTo(refund.getAmount()) != 0) {
                        throw new IllegalStateException("Provider reports refunding " + succeeded.amount().toPlainString()
                                + " for refund " + refund.getId() + " of " + refund.getAmount().toPlainString());
                    }
                    refundService.onProviderSucceeded(refund, intent, succeeded.providerReference(), ACTOR);
                } else {
                    PaymentState previousState = intent.markReversed(succeeded.amount());
                    ledgerService.reversal(intent.getId(), intent.getMerchantId(), intent.getCurrency(), intent.getReversedAmount());
                    auditService.recordStateChange(intent.getId(), previousState, intent.getState(), ACTOR,
                            "Provider confirmed the reversal.", "reversedAmount=" + intent.getReversedAmount().toPlainString()
                                    + ",providerReference=" + succeeded.providerReference());
                }
                yield "succeeded";
            }
            case ProviderResult.Rejected rejected -> {
                command.fail(rejected.reason(), now);
                if (payout) {
                    payoutService.onProviderRefused(batch, rejected.reason(), ACTOR);
                } else if (call.type() == CommandType.REFUND) {
                    RefundRequest refund = refundRepository.findById(call.refundId()).orElseThrow();
                    refundService.onProviderRejected(refund, intent, rejected.reason(), ACTOR);
                } else {
                    reviewService.openCase(ReviewReason.PROVIDER_REJECTED_OPERATION, OutboxService.AGGREGATE,
                            command.getId().toString(), intent.getId(),
                            "Provider refused the reversal of payment " + intent.getId() + ": " + rejected.reason()
                                    + ". The payment still waits for a reversal; RETRY_PROVIDER_CALL sends it again.", ACTOR);
                    auditService.record(OutboxService.AGGREGATE, command.getId().toString(), intent.getId(),
                            "REVERSAL_FAILED", ACTOR, "Provider refused the reversal: " + rejected.reason(),
                            "idempotencyKey=" + command.getIdempotencyKey());
                }
                yield "rejected";
            }
            case ProviderResult.Unknown unknown -> {
                if (command.getAttempts() >= maxAttempts || attempt.escalateNow()) {
                    escalate(command, intent, unknown.reason(), now);
                    yield "escalated";
                }
                command.retryLater(unknown.reason(), now.plus(backoff(command.getAttempts())), now);
                yield "unknown";
            }
        };
        meterRegistry.counter("payflow.outbox.calls", "type", call.type().name(), "result", resultName).increment();
        log.info("Outbox command {} {} attempt={} result={}", command.getId(), call.type(), command.getAttempts(), resultName);
    }

    /** A failure to prepare or record counts as an attempt: retried with backoff, escalated when attempts run out. */
    private void afterFailure(UUID id, String reason) {
        try {
            transactions.executeWithoutResult(status -> {
                Optional<OutboxCommandRepository.Subject> subject = commandRepository.findSubjectById(id);
                if (subject.isEmpty()) {
                    return;
                }
                PaymentIntent intent = null;
                if (subject.get().getCommandType() == CommandType.PAYOUT) {
                    batchRepository.findForUpdate(subject.get().getSettlementBatchId()).orElseThrow();
                } else {
                    intent = paymentIntentRepository.findForUpdate(subject.get().getPaymentIntentId()).orElseThrow();
                }
                OutboxCommand command = commandRepository.findById(id).orElseThrow();
                if (command.getStatus() != CommandStatus.PENDING || !workerId.equals(command.getLeaseOwner())) {
                    return;
                }
                Instant now = clock.instant();
                command.recordAttempt(now);
                if (command.getAttempts() >= maxAttempts) {
                    escalate(command, intent, reason, now);
                } else {
                    command.retryLater(reason, now.plus(backoff(command.getAttempts())), now);
                }
                meterRegistry.counter("payflow.outbox.calls", "type", command.getCommandType().name(), "result", "error").increment();
            });
        } catch (RuntimeException ex) {
            // The lease expires and another attempt follows; dispatch is already committed, so it looks up first.
            log.error("Outbox command {} could not record its failure; it will be retried after its lease expires", id, ex);
        }
    }

    /** @param intent the payment the call is about; null for payouts */
    private void escalate(OutboxCommand command, PaymentIntent intent, String reason, Instant now) {
        command.escalate(reason, now);
        UUID paymentIntentId = intent == null ? null : intent.getId();
        reviewService.openCase(ReviewReason.PROVIDER_OUTCOME_UNKNOWN, OutboxService.AGGREGATE,
                command.getId().toString(), paymentIntentId,
                "No definite outcome after " + command.getAttempts() + " attempt(s) for " + command.getCommandType() + " "
                        + command.getReferenceId() + ": " + reason
                        + ". Retrying is safe: the provider deduplicates by idempotency key.", ACTOR);
        auditService.record(OutboxService.AGGREGATE, command.getId().toString(), paymentIntentId,
                "PROVIDER_CALL_ESCALATED", ACTOR, reason, "attempts=" + command.getAttempts());
    }

    /** Exponential backoff with "equal jitter": half fixed, half random, so retries spread out but never fire instantly. */
    Duration backoff(int attempts) {
        long exponential = baseBackoff.toMillis() * (1L << Math.min(Math.max(attempts, 1) - 1, 20));
        long capped = Math.min(exponential, maxBackoff.toMillis());
        long half = capped / 2;
        return Duration.ofMillis(half + (half == 0 ? 0 : ThreadLocalRandom.current().nextLong(half + 1)));
    }

    /**
     * @param escalateNow no further attempt can be made safely (the provider no longer knows the key)
     */
    private record Attempt(ProviderResult result, boolean escalateNow) {
    }

    /**
     * @param paymentIntentId the payment a refund or reversal is about; null for payouts
     * @param batchId         the settlement batch a payout is about; null otherwise
     * @param reference       for payouts: the reference the provider puts on the bank transfer
     * @param lookupOnly      the first dispatch is older than the provider's key retention: ask, never resend
     */
    private record Call(UUID commandId, CommandType type, UUID paymentIntentId, UUID batchId, UUID refundId,
                        String idempotencyKey, String providerTransactionId, String reference, String merchantId,
                        BigDecimal amount, String currency, List<String> providerTransactionIds,
                        boolean previouslyDispatched, boolean lookupOnly, Instant dispatchedAt) {
        String subject() {
            return type == CommandType.PAYOUT ? "payout " + reference : "transaction " + providerTransactionId;
        }
    }
}
