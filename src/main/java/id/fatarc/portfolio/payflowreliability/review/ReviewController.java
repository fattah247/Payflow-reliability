package id.fatarc.portfolio.payflowreliability.review;

import id.fatarc.portfolio.payflowreliability.review.dto.ResolveReviewCaseRequest;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/review-cases")
public class ReviewController {
    private final ReviewService reviewService;

    public ReviewController(ReviewService reviewService) {
        this.reviewService = reviewService;
    }

    @GetMapping
    public List<ReviewCase> listOpenCases() {
        return reviewService.listOpenCases();
    }

    @PostMapping("/{reviewCaseId}/resolve")
    public ReviewCase resolve(@PathVariable UUID reviewCaseId, @Valid @RequestBody ResolveReviewCaseRequest request) {
        return reviewService.resolve(reviewCaseId, request);
    }
}
