package id.fatarc.portfolio.payflowreliability.review.dto;

import id.fatarc.portfolio.payflowreliability.review.ReviewAction;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * The deciding operator is the authenticated caller, never a field in the body.
 *
 * @param action the decision; defaults to ACKNOWLEDGE. A case's {@code allowedActions} lists what is valid.
 */
public record ResolveReviewCaseRequest(
        @NotBlank @Size(max = 1000) String note,
        ReviewAction action
) {
}
