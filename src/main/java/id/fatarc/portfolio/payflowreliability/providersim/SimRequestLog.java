package id.fatarc.portfolio.payflowreliability.providersim;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;

/** Writes the provider's request log in its own transaction, so refusals are recorded too. */
@Component
@ConditionalOnProperty(name = "payflow.provider-sim.enabled", havingValue = "true")
public class SimRequestLog {
    private final SimApiRequestRepository requests;
    private final Clock clock;

    public SimRequestLog(SimApiRequestRepository requests, Clock clock) {
        this.requests = requests;
        this.clock = clock;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void record(String operation, String idempotencyKey, String requestHash, SimApiRequest.Outcome outcome) {
        requests.save(new SimApiRequest(operation, idempotencyKey, requestHash, outcome, clock.instant()));
    }
}
