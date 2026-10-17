package id.fatarc.portfolio.payflowreliability.payment;

import id.fatarc.portfolio.payflowreliability.audit.AuditService;
import id.fatarc.portfolio.payflowreliability.payment.dto.ProviderWebhookRequest;
import id.fatarc.portfolio.payflowreliability.provider.PaymentProviderClient;
import id.fatarc.portfolio.payflowreliability.provider.ProviderPaymentStatus;
import id.fatarc.portfolio.payflowreliability.provider.ProviderUnavailableException;
import id.fatarc.portfolio.payflowreliability.risk.RiskService;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Webhooks get lost. For payments waiting on the provider for too long, this asks the provider and
 * feeds the answer through exactly the same rules as a webhook (inbox, mismatch holds, contradictions),
 * marked as a POLL event. Payments the provider never heard of are expired after a grace period;
 * payments that were never started are expired too.
 */
@Component
public class PaymentStatusSweeper {
    private static final Logger log = LoggerFactory.getLogger(PaymentStatusSweeper.class);
    private static final String ACTOR = "system:status-sweeper";
    private static final Set<PaymentState> WAITING_ON_PROVIDER = EnumSet.of(PaymentState.PENDING, PaymentState.AUTHORIZED);
    private static final Set<String> MAPPABLE = Set.of("AUTHORIZED", "SUCCESS", "FAILED", "EXPIRED");

    private final PaymentIntentRepository paymentIntentRepository;
    private final PaymentProviderClient providerClient;
    private final RiskService riskService;
    private final ProviderWebhookService webhookService;
    private final AuditService auditService;
    private final MeterRegistry meterRegistry;
    private final Clock clock;
    private final TransactionTemplate transactions;
    private final Duration defaultStuckAfter;
    private final Duration recheckInterval;
    private final Duration pendingExpiry;
    private final Duration createdExpiry;
    private final int batchSize;

    public PaymentStatusSweeper(PaymentIntentRepository paymentIntentRepository,
                                PaymentProviderClient providerClient,
                                RiskService riskService,
                                ProviderWebhookService webhookService,
                                AuditService auditService,
                                MeterRegistry meterRegistry,
                                Clock clock,
                                PlatformTransactionManager transactionManager,
                                @Value("${payflow.sweeper.stuck-after:15m}") Duration defaultStuckAfter,
                                @Value("${payflow.sweeper.recheck-interval:15m}") Duration recheckInterval,
                                @Value("${payflow.sweeper.pending-expiry:24h}") Duration pendingExpiry,
                                @Value("${payflow.sweeper.created-expiry:24h}") Duration createdExpiry,
                                @Value("${payflow.sweeper.batch-size:50}") int batchSize) {
        this.paymentIntentRepository = paymentIntentRepository;
        this.providerClient = providerClient;
        this.riskService = riskService;
        this.webhookService = webhookService;
        this.auditService = auditService;
        this.meterRegistry = meterRegistry;
        this.clock = clock;
        this.transactions = new TransactionTemplate(transactionManager);
        this.defaultStuckAfter = defaultStuckAfter;
        this.recheckInterval = recheckInterval;
        this.pendingExpiry = pendingExpiry;
        this.createdExpiry = createdExpiry;
        this.batchSize = batchSize;
    }

    /**
     * @param recovered payments whose missing provider event was applied from the provider's answer
     * @param errors    payments the provider could not be asked about (retried on the next sweep)
     */
    public record SweepReport(int checked, int recovered, int sentToReview, int expired, int unchanged, int errors) {
    }

    public SweepReport runOnce() {
        return runOnce(defaultStuckAfter);
    }

    public SweepReport runOnce(Duration stuckAfter) {
        Instant now = clock.instant();
        int expired = expireNeverStarted(now) + riskService.expireUnconfirmed(null);
        List<UUID> stuck = transactions.execute(status -> paymentIntentRepository.findStuck(WAITING_ON_PROVIDER,
                now.minus(stuckAfter), now.minus(recheckInterval), PageRequest.of(0, batchSize)));
        int recovered = 0;
        int review = 0;
        int unchanged = 0;
        int errors = 0;
        for (UUID paymentId : stuck) {
            Optional<ProviderPaymentStatus> status;
            try {
                status = providerClient.paymentStatus(paymentId);
            } catch (ProviderUnavailableException ex) {
                errors++;
                log.warn("Status sweep could not ask the provider about {}: {}", paymentId, ex.getMessage());
                continue;
            }
            String result = transactions.execute(tx -> reconcile(paymentId, status, now));
            switch (result == null ? "unchanged" : result) {
                case "recovered" -> recovered++;
                case "review" -> review++;
                case "expired" -> expired++;
                default -> unchanged++;
            }
        }
        meterRegistry.counter("payflow.sweeper.recovered").increment(recovered);
        meterRegistry.counter("payflow.sweeper.expired").increment(expired);
        SweepReport report = new SweepReport(stuck.size(), recovered, review, expired, unchanged, errors);
        if (stuck.size() + expired > 0) {
            log.info("Status sweep {}", report);
        }
        return report;
    }

    private String reconcile(UUID paymentId, Optional<ProviderPaymentStatus> status, Instant now) {
        PaymentIntent intent = paymentIntentRepository.findForUpdate(paymentId).orElseThrow();
        if (!WAITING_ON_PROVIDER.contains(intent.getState())) {
            return "unchanged";
        }
        intent.markProviderChecked(now);
        if (status.isPresent() && MAPPABLE.contains(status.get().status())) {
            ProviderPaymentStatus known = status.get();
            if (known.status().equals(intent.getState().name())) {
                return "unchanged";
            }
            ProviderWebhookRequest event = new ProviderWebhookRequest(
                    "poll-" + paymentId + "-" + known.status() + "-" + known.providerTransactionId(),
                    known.providerTransactionId(), paymentId, ProviderStatus.valueOf(known.status()),
                    known.amount(), known.currency());
            WebhookResult result = webhookService.process(event, EventSource.POLL);
            return switch (result.outcome()) {
                case APPLIED -> "recovered";
                case SENT_TO_REVIEW -> "review";
                default -> "unchanged";
            };
        }
        if (status.isEmpty() && intent.getState() == PaymentState.PENDING
                && intent.getUpdatedAt().isBefore(now.minus(pendingExpiry))) {
            PaymentState previousState = intent.expire();
            auditService.recordStateChange(intent.getId(), previousState, intent.getState(), ACTOR,
                    "The provider has no record of this payment after " + pendingExpiry + "; expired.",
                    "lastUpdate=" + intent.getUpdatedAt());
            return "expired";
        }
        return "unchanged";
    }

    private int expireNeverStarted(Instant now) {
        List<UUID> created = transactions.execute(status -> paymentIntentRepository.findStuck(
                EnumSet.of(PaymentState.CREATED), now.minus(createdExpiry), now, PageRequest.of(0, batchSize)));
        int expired = 0;
        for (UUID paymentId : created) {
            Boolean done = transactions.execute(status -> {
                PaymentIntent intent = paymentIntentRepository.findForUpdate(paymentId).orElseThrow();
                if (intent.getState() != PaymentState.CREATED) {
                    return false;
                }
                PaymentState previousState = intent.expire();
                auditService.recordStateChange(intent.getId(), previousState, intent.getState(), ACTOR,
                        "Never started within " + createdExpiry + "; expired.", "createdAt=" + intent.getCreatedAt());
                return true;
            });
            if (Boolean.TRUE.equals(done)) {
                expired++;
            }
        }
        return expired;
    }
}
