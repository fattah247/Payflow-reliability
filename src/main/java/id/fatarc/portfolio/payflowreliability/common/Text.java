package id.fatarc.portfolio.payflowreliability.common;

public final class Text {
    private Text() {
    }

    /** Shortens text to fit a bounded column, marking the cut, so evidence writes never fail on length. */
    public static String fit(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, maxLength - 1) + "…";
    }
}
