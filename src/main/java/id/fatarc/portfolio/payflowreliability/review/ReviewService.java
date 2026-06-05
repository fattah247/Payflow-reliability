package id.fatarc.portfolio.payflowreliability.review;

import id.fatarc.portfolio.payflowreliability.audit.AuditService;
import id.fatarc.portfolio.payflowreliability.common.BusinessException;
import id.fatarc.portfolio.payflowreliability.review.dto.ResolveReviewCaseRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Service
public class ReviewService {
    private final ReviewCaseRepository reviewCaseRepository;
    private final AuditService auditService;

    public ReviewService(ReviewCaseRepository reviewCaseRepository, AuditService auditService) {
        this.reviewCaseRepository = reviewCaseRepository;
        this.auditService = auditService;
    }

    @Transactional(readOnly = true)
    public List<ReviewCase> listOpenCases() {
        return reviewCaseRepository.findByStatusOrderByCreatedAtAsc(ReviewStatus.OPEN);
    }

    @Transactional
    public ReviewCase openCase(ReviewReason reason,
                               String referenceType,
                               String referenceId,
                               String details,
                               String actor) {
        return reviewCaseRepository
                .findFirstByStatusAndReasonAndReferenceTypeAndReferenceIdOrderByCreatedAtAsc(
                        ReviewStatus.OPEN,
                        reason,
                        referenceType,
                        referenceId
                )
                .orElseGet(() -> reviewCaseRepository.save(new ReviewCase(
                        reason,
                        referenceType,
                        referenceId,
                        details,
                        actor
                )));
    }

    @Transactional
    public ReviewCase resolve(UUID reviewCaseId, ResolveReviewCaseRequest request) {
        ReviewCase reviewCase = reviewCaseRepository.findById(reviewCaseId)
                .orElseThrow(() -> new BusinessException("Review case not found: " + reviewCaseId));

        try {
            reviewCase.resolve(request.actor(), request.note());
        } catch (IllegalStateException ex) {
            throw new BusinessException(ex.getMessage());
        }

        auditService.record(
                "MANUAL_REVIEW",
                reviewCase.getId().toString(),
                "MANUAL_REVIEW_RESOLVED",
                request.actor(),
                request.note(),
                "referenceType=" + reviewCase.getReferenceType() + ",referenceId=" + reviewCase.getReferenceId()
        );

        return reviewCaseRepository.save(reviewCase);
    }
}
