package id.fatarc.portfolio.payflowreliability.review;

import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Opens and queries review cases. Resolution decisions live in {@link ReviewWorkflowService}. */
@Service
public class ReviewService {
    private static final Logger log = LoggerFactory.getLogger(ReviewService.class);

    private final ReviewCaseRepository reviewCaseRepository;
    private final MeterRegistry meterRegistry;

    public ReviewService(ReviewCaseRepository reviewCaseRepository, MeterRegistry meterRegistry) {
        this.reviewCaseRepository = reviewCaseRepository;
        this.meterRegistry = meterRegistry;
    }

    /**
     * Opens a case, or returns the open case that already exists for the same reason and
     * subject, so a repeating problem shows up once in the queue instead of once per retry.
     */
    @Transactional
    public ReviewCase openCase(ReviewReason reason,
                               String referenceType,
                               String referenceId,
                               UUID paymentIntentId,
                               String details,
                               String actor) {
        return reviewCaseRepository
                .findFirstByStatusAndReasonAndReferenceTypeAndReferenceIdOrderByCreatedAtAsc(
                        ReviewStatus.OPEN, reason, referenceType, referenceId)
                .orElseGet(() -> {
                    ReviewCase created = reviewCaseRepository.save(
                            new ReviewCase(reason, referenceType, referenceId, paymentIntentId, details, actor));
                    meterRegistry.counter("payflow.review.cases.opened", "reason", reason.name()).increment();
                    log.info("Review case opened id={} reason={} reference={}:{} paymentIntentId={}",
                            created.getId(), reason, referenceType, referenceId, paymentIntentId);
                    return created;
                });
    }

    /** Oldest first: the queue is worked in arrival order. Any filter may be null. */
    @Transactional(readOnly = true)
    public List<ReviewCase> findCases(ReviewStatus status, ReviewReason reason, UUID paymentIntentId) {
        Specification<ReviewCase> filter = (root, query, cb) -> cb.conjunction();
        if (status != null) {
            filter = filter.and((root, query, cb) -> cb.equal(root.get("status"), status));
        }
        if (reason != null) {
            filter = filter.and((root, query, cb) -> cb.equal(root.get("reason"), reason));
        }
        if (paymentIntentId != null) {
            filter = filter.and((root, query, cb) -> cb.equal(root.get("paymentIntentId"), paymentIntentId));
        }
        return reviewCaseRepository.findAll(filter, Sort.by("createdAt", "id"));
    }

    @Transactional(readOnly = true)
    public Optional<ReviewCase> findOpenCase(UUID paymentIntentId, ReviewReason reason) {
        return reviewCaseRepository.findFirstByStatusAndPaymentIntentIdAndReasonOrderByCreatedAtAsc(
                ReviewStatus.OPEN, paymentIntentId, reason);
    }

    @Transactional(readOnly = true)
    public List<ReviewCase> casesForPayment(UUID paymentIntentId) {
        return reviewCaseRepository.findByPaymentIntentIdOrderByCreatedAtAsc(paymentIntentId);
    }
}
