package id.fatarc.portfolio.payflowreliability.review;

import id.fatarc.portfolio.payflowreliability.common.Keyset;
import id.fatarc.portfolio.payflowreliability.common.PageCursor;
import id.fatarc.portfolio.payflowreliability.common.PageSlice;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Opens and queries review cases. Resolution decisions live in {@link ReviewWorkflowService}. */
@Service
public class ReviewService {
    private static final Logger log = LoggerFactory.getLogger(ReviewService.class);

    private final ReviewCaseRepository reviewCaseRepository;
    private final MeterRegistry meterRegistry;
    private final Clock clock;

    public ReviewService(ReviewCaseRepository reviewCaseRepository, MeterRegistry meterRegistry, Clock clock) {
        this.reviewCaseRepository = reviewCaseRepository;
        this.meterRegistry = meterRegistry;
        this.clock = clock;
    }

    /**
     * Opens a case, or returns the unresolved case that already exists for the same reason and
     * subject, so a repeating problem shows up once in the queue instead of once per retry. On
     * PostgreSQL a partial unique index also enforces this under concurrent writers.
     */
    @Transactional
    public ReviewCase openCase(ReviewReason reason,
                               String referenceType,
                               String referenceId,
                               UUID paymentIntentId,
                               String details,
                               String actor) {
        return reviewCaseRepository
                .findFirstByStatusInAndReasonAndReferenceTypeAndReferenceIdOrderByCreatedAtAsc(
                        ReviewStatus.UNRESOLVED, reason, referenceType, referenceId)
                .orElseGet(() -> {
                    ReviewCase created = reviewCaseRepository.saveAndFlush(
                            new ReviewCase(reason, referenceType, referenceId, paymentIntentId, details, actor, clock.instant()));
                    meterRegistry.counter("payflow.review.cases.opened", "reason", reason.name()).increment();
                    log.info("Review case opened id={} reason={} reference={}:{} paymentIntentId={}",
                            created.getId(), reason, referenceType, referenceId, paymentIntentId);
                    return created;
                });
    }

    /** Resolves every unresolved case about a subject that no longer needs a decision (for example a cancelled refund). */
    @Transactional
    public void closeForSubject(String referenceType, String referenceId, String actor, String note) {
        reviewCaseRepository.findByStatusInAndReferenceTypeAndReferenceId(ReviewStatus.UNRESOLVED, referenceType, referenceId)
                .forEach(reviewCase -> reviewCase.resolve(actor, note, ReviewAction.ACKNOWLEDGE, null, clock.instant()));
    }

    /** Oldest first: the queue is worked in arrival order. Null filters are ignored. */
    @Transactional(readOnly = true)
    public List<ReviewCase> findCases(Collection<ReviewStatus> statuses, ReviewReason reason, UUID paymentIntentId) {
        return reviewCaseRepository.findAll(filter(statuses, reason, paymentIntentId), Sort.by("createdAt", "id"));
    }

    /** One page of {@link #findCases}, oldest first, starting after {@code after}. */
    @Transactional(readOnly = true)
    public PageSlice<ReviewCase> pageCases(Collection<ReviewStatus> statuses, ReviewReason reason, UUID paymentIntentId,
                                           PageCursor after, Integer limit) {
        return Keyset.page(reviewCaseRepository, filter(statuses, reason, paymentIntentId), "createdAt", true, after, limit,
                reviewCase -> new PageCursor(reviewCase.getCreatedAt(), reviewCase.getId()));
    }

    private static Specification<ReviewCase> filter(Collection<ReviewStatus> statuses, ReviewReason reason, UUID paymentIntentId) {
        Specification<ReviewCase> filter = (root, query, cb) -> cb.conjunction();
        if (statuses != null) {
            filter = filter.and((root, query, cb) -> root.get("status").in(statuses));
        }
        if (reason != null) {
            filter = filter.and((root, query, cb) -> cb.equal(root.get("reason"), reason));
        }
        if (paymentIntentId != null) {
            filter = filter.and((root, query, cb) -> cb.equal(root.get("paymentIntentId"), paymentIntentId));
        }
        return filter;
    }

    /** An unresolved case of this reason about this payment, if any. */
    @Transactional(readOnly = true)
    public Optional<ReviewCase> findOpenCase(UUID paymentIntentId, ReviewReason reason) {
        return reviewCaseRepository.findFirstByStatusInAndPaymentIntentIdAndReasonOrderByCreatedAtAsc(
                ReviewStatus.UNRESOLVED, paymentIntentId, reason);
    }

    @Transactional(readOnly = true)
    public List<ReviewCase> casesForPayment(UUID paymentIntentId) {
        return reviewCaseRepository.findByPaymentIntentIdOrderByCreatedAtAsc(paymentIntentId);
    }
}
