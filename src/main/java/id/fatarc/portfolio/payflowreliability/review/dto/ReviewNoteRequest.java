package id.fatarc.portfolio.payflowreliability.review.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record ReviewNoteRequest(@NotBlank @Size(max = 1000) String note) {
}
