package id.fatarc.portfolio.payflowreliability.review.dto;

import id.fatarc.portfolio.payflowreliability.review.ReviewAction;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * @param action the decision to apply; defaults to ACKNOWLEDGE. A case's {@code allowedActions} lists what is valid.
 */
public record ResolveReviewCaseRequest(
        @NotBlank @Size(max = 100) String actor,
        @NotBlank @Size(max = 1000) String note,
        ReviewAction action
) {
}
