package id.fatarc.portfolio.payflowreliability.common;

import org.slf4j.MDC;

public final class RequestId {
    public static final String HEADER = "X-Request-Id";
    public static final String MDC_KEY = "requestId";

    private RequestId() {
    }

    /** The correlation id of the current request, or {@code null} outside a request. */
    public static String current() {
        return MDC.get(MDC_KEY);
    }
}
