package id.fatarc.portfolio.payflowreliability.payment;

import id.fatarc.portfolio.payflowreliability.payment.dto.CreatePaymentIntentRequest;
import id.fatarc.portfolio.payflowreliability.payment.dto.PaymentIntentResponse;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.net.URI;
import java.util.UUID;

@RestController
@RequestMapping("/api/payment-intents")
public class PaymentIntentController {
    static final String IDEMPOTENT_REPLAYED_HEADER = "Idempotent-Replayed";

    private final PaymentIntentService paymentIntentService;

    public PaymentIntentController(PaymentIntentService paymentIntentService) {
        this.paymentIntentService = paymentIntentService;
    }

    /** 201 for a new intent; 200 with {@code Idempotent-Replayed: true} when the key was seen before. */
    @PostMapping
    public ResponseEntity<PaymentIntentResponse> create(
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @Valid @RequestBody CreatePaymentIntentRequest request
    ) {
        PaymentIntentCreation creation = paymentIntentService.create(request, idempotencyKey);
        PaymentIntentResponse body = PaymentIntentResponse.from(creation.intent());
        if (creation.replayed()) {
            return ResponseEntity.ok().header(IDEMPOTENT_REPLAYED_HEADER, "true").body(body);
        }
        return ResponseEntity.created(URI.create("/api/payment-intents/" + body.id())).body(body);
    }

    @PostMapping("/{paymentIntentId}/start")
    public PaymentIntentResponse start(@PathVariable UUID paymentIntentId) {
        return PaymentIntentResponse.from(paymentIntentService.start(paymentIntentId));
    }

    @GetMapping("/{paymentIntentId}")
    public PaymentIntentResponse get(@PathVariable UUID paymentIntentId) {
        return PaymentIntentResponse.from(paymentIntentService.getRequired(paymentIntentId));
    }
}
