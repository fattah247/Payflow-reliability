package id.fatarc.portfolio.payflowreliability.reconciliation.breaks;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.UUID;

/**
 * @param batchId for ASSIGN_TO_BATCH: the settlement batch the credit belongs to
 */
public record ResolveBreakRequest(@NotNull BreakAction action, UUID batchId, @NotBlank @Size(max = 1000) String note) {
}
