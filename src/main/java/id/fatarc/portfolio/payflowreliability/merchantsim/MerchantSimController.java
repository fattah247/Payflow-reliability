package id.fatarc.portfolio.payflowreliability.merchantsim;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.net.URI;
import java.util.Map;

/**
 * The simulated shops' webhook receivers ({@code POST /merchant-sim/v1/shops/{shop}/webhooks}), and controls for demos:
 * storing the secret a shop was shown, injecting faults, and reading what each shop received. Local only.
 */
@RestController
@RequestMapping("/merchant-sim/v1/shops/{shop}")
@ConditionalOnProperty(name = "payflow.merchant-sim.enabled", havingValue = "true")
public class MerchantSimController {
    private final MerchantSimulator simulator;

    public MerchantSimController(MerchantSimulator simulator) {
        this.simulator = simulator;
    }

    public record SecretRequest(String secret) {
    }

    /**
     * @param failNext   answer this many deliveries with {@code status} (default 500)
     * @param slowNext   make this many deliveries wait {@code delayMillis} before answering
     */
    public record FaultRequest(Integer failNext, Integer status, Integer slowNext, Long delayMillis) {
    }

    @PostMapping("/webhooks")
    public ResponseEntity<Void> receive(@PathVariable String shop,
                                        @RequestHeader(value = "Payflow-Signature", required = false) String signature,
                                        @RequestBody byte[] body) throws InterruptedException {
        MerchantSimulator.Answer answer = simulator.receive(shop, signature, body);
        if (answer.delayMillis() > 0) {
            Thread.sleep(answer.delayMillis());
        }
        if (answer.status() >= 300 && answer.status() < 400) {
            // Somewhere that would answer 200: a sender that follows redirects would wrongly count this as delivered.
            return ResponseEntity.status(answer.status()).location(URI.create("/merchant-sim/v1/shops/" + shop)).build();
        }
        return ResponseEntity.status(answer.status()).build();
    }

    @PutMapping("/secret")
    public Map<String, Object> secret(@PathVariable String shop, @RequestBody SecretRequest request) {
        simulator.setSecret(shop, request.secret());
        return simulator.view(shop);
    }

    @PostMapping("/faults")
    public Map<String, Object> faults(@PathVariable String shop, @RequestBody FaultRequest request) {
        simulator.inject(shop, request.failNext() == null ? 0 : request.failNext(), request.status(),
                request.slowNext() == null ? 0 : request.slowNext(), request.delayMillis() == null ? 0 : request.delayMillis());
        return simulator.view(shop);
    }

    @GetMapping
    public Map<String, Object> view(@PathVariable String shop) {
        return simulator.view(shop);
    }

    @DeleteMapping
    public ResponseEntity<Void> forget(@PathVariable String shop) {
        simulator.forget(shop);
        return ResponseEntity.noContent().build();
    }
}
