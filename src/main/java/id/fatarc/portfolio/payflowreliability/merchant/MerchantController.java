package id.fatarc.portfolio.payflowreliability.merchant;

import id.fatarc.portfolio.payflowreliability.merchant.dto.IssuedApiKey;
import id.fatarc.portfolio.payflowreliability.merchant.dto.MerchantRegistration;
import id.fatarc.portfolio.payflowreliability.merchant.dto.MerchantView;
import id.fatarc.portfolio.payflowreliability.merchant.dto.RegisterMerchantRequest;
import id.fatarc.portfolio.payflowreliability.security.Caller;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

/** Operator-only. Responses that contain a plaintext key are never stored for idempotent replay. */
@RestController
@RequestMapping("/api/merchants")
public class MerchantController {
    private final MerchantService merchantService;

    public MerchantController(MerchantService merchantService) {
        this.merchantService = merchantService;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public MerchantRegistration register(@Valid @RequestBody RegisterMerchantRequest request, Caller caller) {
        return merchantService.register(request, caller);
    }

    @GetMapping("/{merchantId}")
    public MerchantView get(@PathVariable String merchantId) {
        return merchantService.get(merchantId);
    }

    /** Rotation: issue a new key, move the integration to it, then revoke the old one. */
    @PostMapping("/{merchantId}/api-keys")
    @ResponseStatus(HttpStatus.CREATED)
    public IssuedApiKey issueKey(@PathVariable String merchantId, Caller caller) {
        return merchantService.issueKey(merchantId, caller);
    }

    @DeleteMapping("/{merchantId}/api-keys/{keyId}")
    public MerchantView revokeKey(@PathVariable String merchantId, @PathVariable UUID keyId, Caller caller) {
        return merchantService.revokeKey(merchantId, keyId, caller);
    }
}
