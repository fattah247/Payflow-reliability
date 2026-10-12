package id.fatarc.portfolio.payflowreliability.reconciliation.dto;

import id.fatarc.portfolio.payflowreliability.payment.ProviderStatus;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/** One import is one transaction; the row cap keeps it bounded. Split larger reports. */
public record ReconciliationReportRequest(
        @NotEmpty @Size(max = 1000) List<@Valid @NotNull Row> rows
) {
    public record Row(
            @NotBlank @Size(max = 100) String providerTransactionId,
            @NotNull @Digits(integer = 17, fraction = 2, message = "must have at most 17 integer digits and 2 decimal places") @DecimalMin(value = "0.01") BigDecimal amount,
            @NotBlank @Pattern(regexp = "[A-Z]{3}", message = "must be a 3-letter uppercase ISO currency code") String currency,
            @NotNull ProviderStatus providerStatus,
            LocalDate settlementDate
    ) {
    }
}
