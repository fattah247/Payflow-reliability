package id.fatarc.portfolio.payflowreliability.outbox;

import id.fatarc.portfolio.payflowreliability.audit.AuditService;
import id.fatarc.portfolio.payflowreliability.common.BusinessException;
import id.fatarc.portfolio.payflowreliability.common.ErrorCode;
import id.fatarc.portfolio.payflowreliability.payment.PaymentIntentRepository;
import id.fatarc.portfolio.payflowreliability.payment.PaymentState;
import id.fatarc.portfolio.payflowreliability.security.Caller;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Enqueues provider calls inside the caller's transaction, and lets operators inspect and retry them. */
@Service
public class OutboxService {
    public static final String AGGREGATE = "OUTBOX_COMMAND";

    private final OutboxCommandRepository repository;
    private final PaymentIntentRepository paymentIntentRepository;
    private final AuditService auditService;
    private final Clock clock;

    public OutboxService(OutboxCommandRepository repository, PaymentIntentRepository paymentIntentRepository,
                         AuditService auditService, Clock clock) {
        this.repository = repository;
        this.paymentIntentRepository = paymentIntentRepository;
        this.auditService = auditService;
        this.clock = clock;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public OutboxCommand enqueueRefund(UUID paymentIntentId, UUID refundId) {
        return repository.save(OutboxCommand.refund(paymentIntentId, refundId, clock.instant()));
    }

    /**
     * One live reversal per payment; enqueuing again returns it. After the provider refused one, a new
     * command gets a new idempotency key: the provider would answer a reused key with the cached refusal.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public OutboxCommand enqueueReversal(UUID paymentIntentId) {
        List<OutboxCommand> reversals =
                repository.findByPaymentIntentIdAndCommandTypeOrderByCreatedAtAsc(paymentIntentId, CommandType.REVERSAL);
        return reversals.stream()
                .filter(command -> command.getStatus() != CommandStatus.FAILED && command.getStatus() != CommandStatus.CANCELLED)
                .findFirst()
                .orElseGet(() -> repository.save(OutboxCommand.reversal(paymentIntentId, reversals.size() + 1, clock.instant())));
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public Optional<OutboxCommand> findForRefund(UUID refundId) {
        return repository.findByIdempotencyKey(OutboxCommand.refundKey(refundId));
    }

    /**
     * Operator retry. A call with an unknown outcome is queued again under the same idempotency key (safe:
     * the provider returns the original result for a key it has processed). A refused reversal the payment
     * still needs is sent again as a new command with a new key.
     */
    @Transactional
    public OutboxCommand retry(UUID commandId, Caller caller) {
        // Payment first (the global lock order; a no-op when the caller already holds it), then read the command.
        UUID paymentIntentId = repository.findPaymentIntentIdById(commandId).orElseThrow(() -> notFound(commandId));
        paymentIntentRepository.findForUpdate(paymentIntentId);
        OutboxCommand command = getRequired(commandId);
        if (command.getStatus() == CommandStatus.NEEDS_REVIEW) {
            command.requeue(clock.instant());
            auditService.record(AGGREGATE, command.getId().toString(), command.getPaymentIntentId(), "PROVIDER_CALL_REQUEUED",
                    caller.name(), "Provider call queued again.", "idempotencyKey=" + command.getIdempotencyKey());
            return command;
        }
        if (isRefusedReversalStillNeeded(command)) {
            OutboxCommand next = enqueueReversal(command.getPaymentIntentId());
            auditService.record(AGGREGATE, next.getId().toString(), command.getPaymentIntentId(), "PROVIDER_CALL_RESENT",
                    caller.name(), "Reversal sent again after the provider refused it.",
                    "previousCommandId=" + command.getId() + ",idempotencyKey=" + next.getIdempotencyKey());
            return next;
        }
        throw new BusinessException(ErrorCode.OUTBOX_COMMAND_NOT_RETRYABLE,
                "Only calls with an unknown outcome, or a refused reversal the payment still needs, can be retried; this one is "
                        + command.getStatus() + ".");
    }

    /** A refused reversal leaves the payment waiting for one; an operator can send it again under a new key. */
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean isRefusedReversalStillNeeded(OutboxCommand command) {
        return command.getStatus() == CommandStatus.FAILED && command.getCommandType() == CommandType.REVERSAL
                && paymentIntentRepository.findById(command.getPaymentIntentId())
                .map(intent -> intent.getState() == PaymentState.REVERSAL_REQUIRED)
                .orElse(false)
                && repository.findByPaymentIntentIdAndCommandTypeOrderByCreatedAtAsc(command.getPaymentIntentId(), CommandType.REVERSAL)
                .stream().noneMatch(other -> other.getStatus() == CommandStatus.PENDING || other.getStatus() == CommandStatus.NEEDS_REVIEW);
    }

    @Transactional(readOnly = true)
    public OutboxCommand getRequired(UUID commandId) {
        return repository.findById(commandId).orElseThrow(() -> notFound(commandId));
    }

    @Transactional(readOnly = true)
    public List<OutboxCommand> list(CommandStatus status) {
        return status == null
                ? repository.findAllByOrderByCreatedAtDesc(PageRequest.of(0, 200))
                : repository.findByStatusOrderByCreatedAtAsc(status);
    }

    @Transactional(readOnly = true)
    public List<OutboxCommand> forPayment(UUID paymentIntentId) {
        return repository.findByPaymentIntentIdOrderByCreatedAtAsc(paymentIntentId);
    }

    private static BusinessException notFound(UUID commandId) {
        return new BusinessException(ErrorCode.OUTBOX_COMMAND_NOT_FOUND, "Outbox command not found: " + commandId);
    }
}
