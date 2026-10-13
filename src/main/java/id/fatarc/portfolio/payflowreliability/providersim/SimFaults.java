package id.fatarc.portfolio.payflowreliability.providersim;

import org.springframework.stereotype.Component;

import java.util.EnumMap;
import java.util.Map;

/** Failure injection for the next calls of an operation. In memory: it is a test and demo control. */
@Component
public class SimFaults {
    /** LOOKUP is the provider's "what happened to this idempotency key" endpoint for refunds and reversals. */
    public enum Operation { REFUND, REVERSAL, STATUS, LOOKUP }

    public enum Mode {
        NONE,
        /** 503 before doing anything. */
        UNAVAILABLE,
        /** Applies the operation, then answers too late: the caller times out not knowing it succeeded. */
        TIMEOUT_AFTER_COMMIT,
        /** A definite refusal; for LOOKUP, a 403 as if the caller's credentials were refused. */
        REJECT
    }

    private final Map<Operation, Fault> faults = new EnumMap<>(Operation.class);

    public synchronized void inject(Operation operation, Mode mode, int times) {
        if (mode == Mode.NONE || times <= 0) {
            faults.remove(operation);
        } else {
            faults.put(operation, new Fault(mode, times));
        }
    }

    public synchronized void clear() {
        faults.clear();
    }

    /** The fault for this call, consuming one occurrence. */
    synchronized Mode next(Operation operation) {
        Fault fault = faults.get(operation);
        if (fault == null) {
            return Mode.NONE;
        }
        if (fault.remaining <= 1) {
            faults.remove(operation);
        } else {
            faults.put(operation, new Fault(fault.mode, fault.remaining - 1));
        }
        return fault.mode;
    }

    private record Fault(Mode mode, int remaining) {
    }
}
