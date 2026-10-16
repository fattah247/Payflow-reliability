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
    private final RiskTrafficGenerator riskTrafficGenerator;

    public SimulationController(CheckoutLoadGenerator checkoutLoadGenerator, RiskTrafficGenerator riskTrafficGenerator) {
        this.checkoutLoadGenerator = checkoutLoadGenerator;
        this.riskTrafficGenerator = riskTrafficGenerator;
    }

    /** Runs labelled customers (honest and fraudulent) with device evidence, then discloses the ground truth as labels. */
    @PostMapping("/risk-runs")
    public RiskTrafficGenerator.Report riskRun(@RequestBody RiskTrafficGenerator.Request request) {
        if (request.merchantApiKey() == null || request.phase() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "merchantApiKey and phase are required");
        }
        return riskTrafficGenerator.run(request);
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
