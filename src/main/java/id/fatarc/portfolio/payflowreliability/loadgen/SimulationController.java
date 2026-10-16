package id.fatarc.portfolio.payflowreliability.loadgen;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

/** Operator-only controls for local simulations. Absent unless the load generator is enabled. */
@RestController
@RequestMapping("/api/simulations")
@ConditionalOnProperty(name = "payflow.loadgen.checkout.enabled", havingValue = "true")
public class SimulationController {
    private final CheckoutLoadGenerator checkoutLoadGenerator;

    public SimulationController(CheckoutLoadGenerator checkoutLoadGenerator) {
        this.checkoutLoadGenerator = checkoutLoadGenerator;
    }

    /** Runs simulated customers through the real API and returns the simulation's ground truth. */
    @PostMapping("/checkout-runs")
    public CheckoutRun.Report checkoutRun(@RequestBody CheckoutRun.Request request) {
        if (request.merchantApiKey() == null || request.phase() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "merchantApiKey and phase are required");
        }
        return checkoutLoadGenerator.run(request);
    }
}
