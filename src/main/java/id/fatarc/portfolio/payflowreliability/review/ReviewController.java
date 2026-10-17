package id.fatarc.portfolio.payflowreliability.review;

import id.fatarc.portfolio.payflowreliability.common.BusinessException;
import id.fatarc.portfolio.payflowreliability.common.ErrorCode;
import id.fatarc.portfolio.payflowreliability.common.PageCursor;
import id.fatarc.portfolio.payflowreliability.common.PageResponses;
import id.fatarc.portfolio.payflowreliability.review.dto.ResolveReviewCaseRequest;
import id.fatarc.portfolio.payflowreliability.review.dto.ReviewCaseView;
import id.fatarc.portfolio.payflowreliability.review.dto.ReviewNoteRequest;
import id.fatarc.portfolio.payflowreliability.security.Caller;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Collection;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

@RestController
@RequestMapping("/api/review-cases")
public class ReviewController {
    private final ReviewWorkflowService reviewWorkflowService;

    public ReviewController(ReviewWorkflowService reviewWorkflowService) {
        this.reviewWorkflowService = reviewWorkflowService;
    }

    /**
     * The review queue, oldest first. {@code status}: UNRESOLVED (default: open or awaiting approval),
     * OPEN, AWAITING_APPROVAL, RESOLVED, or ALL.
     */
    @GetMapping
    public ResponseEntity<List<ReviewCaseView>> list(@RequestParam(defaultValue = "UNRESOLVED") String status,
                                                     @RequestParam(required = false) ReviewReason reason,
                                                     @RequestParam(required = false) UUID paymentIntentId,
                                                     @RequestParam(required = false) Integer limit,
                                                     @RequestParam(required = false) String after) {
        return PageResponses.of(reviewWorkflowService.page(parseStatus(status), reason, paymentIntentId,
                PageCursor.decode(after), limit));
    }

    @GetMapping("/{reviewCaseId}")
    public ReviewCaseView get(@PathVariable UUID reviewCaseId) {
        return reviewWorkflowService.get(reviewCaseId);
    }

    /** 200 when applied; 202 when the decision needs a second operator's approval. */
    @PostMapping("/{reviewCaseId}/resolve")
    public ResponseEntity<ReviewCaseView> resolve(@PathVariable UUID reviewCaseId,
                                                  @Valid @RequestBody ResolveReviewCaseRequest request, Caller caller) {
        ReviewCaseView view = reviewWorkflowService.resolve(reviewCaseId, request, caller);
        return ResponseEntity.status(view.status() == ReviewStatus.AWAITING_APPROVAL ? HttpStatus.ACCEPTED : HttpStatus.OK)
                .body(view);
    }

    @PostMapping("/{reviewCaseId}/approve")
    public ReviewCaseView approve(@PathVariable UUID reviewCaseId, @Valid @RequestBody ReviewNoteRequest request,
                                  Caller caller) {
        return reviewWorkflowService.approve(reviewCaseId, request.note(), caller);
    }

    @PostMapping("/{reviewCaseId}/reject-proposal")
    public ReviewCaseView rejectProposal(@PathVariable UUID reviewCaseId, @Valid @RequestBody ReviewNoteRequest request,
                                         Caller caller) {
        return reviewWorkflowService.rejectProposal(reviewCaseId, request.note(), caller);
    }

    private static Collection<ReviewStatus> parseStatus(String status) {
        return switch (status.toUpperCase(Locale.ROOT)) {
            case "ALL" -> null;
            case "UNRESOLVED" -> ReviewStatus.UNRESOLVED;
            case "OPEN" -> EnumSet.of(ReviewStatus.OPEN);
            case "AWAITING_APPROVAL" -> EnumSet.of(ReviewStatus.AWAITING_APPROVAL);
            case "RESOLVED" -> EnumSet.of(ReviewStatus.RESOLVED);
            default -> throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                    "status must be UNRESOLVED, OPEN, AWAITING_APPROVAL, RESOLVED, or ALL");
        };
    }
}
