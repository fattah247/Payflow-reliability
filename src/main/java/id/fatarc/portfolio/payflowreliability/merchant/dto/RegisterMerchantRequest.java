package id.fatarc.portfolio.payflowreliability.merchant.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record RegisterMerchantRequest(
        @NotBlank @Pattern(regexp = "[A-Za-z0-9][A-Za-z0-9._-]{2,99}",
                message = "must be 3-100 letters, digits, '.', '_' or '-'") String merchantId,
        @NotBlank @Size(max = 200) String displayName
) {
}
