package id.fatarc.portfolio.payflowreliability.payment;

import id.fatarc.portfolio.payflowreliability.payment.dto.CancelPaymentIntentRequest;
import id.fatarc.portfolio.payflowreliability.payment.dto.ConfirmStepUpRequest;
import id.fatarc.portfolio.payflowreliability.payment.dto.CreatePaymentIntentRequest;
import id.fatarc.portfolio.payflowreliability.payment.dto.PaymentIntentResponse;
import id.fatarc.portfolio.payflowreliability.security.Caller;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.net.URI;
import java.util.List;
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
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @Valid @RequestBody CreatePaymentIntentRequest request,
            Caller caller
    ) {
        PaymentIntentCreation creation = paymentIntentService.create(request, idempotencyKey, caller);
        PaymentIntentResponse body = PaymentIntentResponse.from(creation.intent());
        if (creation.replayed()) {
            return ResponseEntity.ok().header(IDEMPOTENT_REPLAYED_HEADER, "true").body(body);
        }
        return ResponseEntity.created(URI.create("/api/payment-intents/" + body.id())).body(body);
    }

    @PostMapping("/{paymentIntentId}/start")
    public PaymentIntentResponse start(@PathVariable UUID paymentIntentId, Caller caller) {
        return PaymentIntentResponse.from(paymentIntentService.start(paymentIntentId, caller));
    }

    /** The customer confirmed a device-risk step-up with the method the decision required. Repeating it is a no-op. */
    @PostMapping("/{paymentIntentId}/confirm")
    public PaymentIntentResponse confirm(@PathVariable UUID paymentIntentId, @Valid @RequestBody ConfirmStepUpRequest request,
                                         Caller caller) {
        return PaymentIntentResponse.from(paymentIntentService.confirm(paymentIntentId, request.method(), caller));
    }

    /** Withdraws a payment the customer has not paid, for example an abandoned checkout. Repeating it is a no-op. */
    @PostMapping("/{paymentIntentId}/cancel")
    public PaymentIntentResponse cancel(@PathVariable UUID paymentIntentId, @Valid @RequestBody CancelPaymentIntentRequest request,
                                        Caller caller) {
        return PaymentIntentResponse.from(paymentIntentService.cancel(paymentIntentId, request.reason(), caller));
    }

    /** The caller's payments for one merchant reference, newest first (at most 50). Operators also pass merchantId. */
    @GetMapping
    public List<PaymentIntentResponse> findByReference(@RequestParam String merchantReference,
                                                       @RequestParam(required = false) String merchantId, Caller caller) {
        return paymentIntentService.findByReference(merchantReference, merchantId, caller).stream()
                .map(PaymentIntentResponse::from).toList();
    }

    @GetMapping("/{paymentIntentId}")
    public PaymentIntentResponse get(@PathVariable UUID paymentIntentId, Caller caller) {
        return PaymentIntentResponse.from(paymentIntentService.getAccessible(paymentIntentId, caller));
    }
}
