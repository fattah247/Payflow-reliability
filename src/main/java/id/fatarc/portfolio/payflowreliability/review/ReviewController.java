package id.fatarc.portfolio.payflowreliability.review;

import id.fatarc.portfolio.payflowreliability.common.BusinessException;
import id.fatarc.portfolio.payflowreliability.common.ErrorCode;
import id.fatarc.portfolio.payflowreliability.review.dto.ResolveReviewCaseRequest;
import id.fatarc.portfolio.payflowreliability.review.dto.ReviewCaseView;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/review-cases")
public class ReviewController {
    private final ReviewWorkflowService reviewWorkflowService;

    public ReviewController(ReviewWorkflowService reviewWorkflowService) {
        this.reviewWorkflowService = reviewWorkflowService;
    }

    /**
     * The review queue, oldest first. Defaults to open cases; {@code status=ALL} includes resolved ones.
     */
    @GetMapping
    public List<ReviewCaseView> list(@RequestParam(defaultValue = "OPEN") String status,
                                     @RequestParam(required = false) ReviewReason reason,
                                     @RequestParam(required = false) UUID paymentIntentId) {
        return reviewWorkflowService.list(parseStatus(status), reason, paymentIntentId);
    }

    @GetMapping("/{reviewCaseId}")
    public ReviewCaseView get(@PathVariable UUID reviewCaseId) {
        return reviewWorkflowService.get(reviewCaseId);
    }

    @PostMapping("/{reviewCaseId}/resolve")
    public ReviewCaseView resolve(@PathVariable UUID reviewCaseId, @Valid @RequestBody ResolveReviewCaseRequest request) {
        return reviewWorkflowService.resolve(reviewCaseId, request);
    }

    private static ReviewStatus parseStatus(String status) {
        if ("ALL".equalsIgnoreCase(status)) {
            return null;
        }
        try {
            return ReviewStatus.valueOf(status.toUpperCase());
        } catch (IllegalArgumentException ex) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "status must be OPEN, RESOLVED, or ALL");
        }
    }
}
