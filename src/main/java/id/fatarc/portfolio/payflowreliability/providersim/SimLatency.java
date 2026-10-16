package id.fatarc.portfolio.payflowreliability.providersim;

import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * The provider being slow without being down: its hosted checkout page and its payment responses take longer,
 * and its webhooks pile up in its own backlog. Its API still answers and our health checks still pass.
 *
 * <p>Each slow answer declares its latency (header {@code X-Provider-Latency-Ms}) and actually waits that long
 * multiplied by {@code payflow.provider-sim.latency-time-scale}, so a demo can show minutes of slowness in seconds
 * while decisions made on the declared latency stay exactly reproducible. In memory: a test and demo control.
 */
@Component
public class SimLatency {
    public record Mode(long checkoutPageMillis, long payMillis, boolean holdWebhooks) {
        static final Mode NORMAL = new Mode(0, 0, false);

        public boolean slow() {
            return this != NORMAL && (checkoutPageMillis > 0 || payMillis > 0 || holdWebhooks);
        }
    }

    private final double timeScale;
    private volatile Mode mode = Mode.NORMAL;

    public SimLatency(SimulatorProperties properties) {
        this.timeScale = properties.latencyTimeScale();
    }

    public void slow(Mode slow) {
        this.mode = slow;
    }

    public void recover() {
        this.mode = Mode.NORMAL;
    }

    public Mode mode() {
        return mode;
    }

    boolean holdingWebhooks() {
        return mode.holdWebhooks();
    }

    /** Waits for the declared latency (scaled) and returns the declared value. */
    long checkoutPage() throws InterruptedException {
        return pause(mode.checkoutPageMillis());
    }

    long pay() throws InterruptedException {
        return pause(mode.payMillis());
    }

    private long pause(long declaredMillis) throws InterruptedException {
        long real = Math.round(declaredMillis * timeScale);
        if (real > 0) {
            Thread.sleep(Duration.ofMillis(real));
        }
        return declaredMillis;
    }
}
