package id.fatarc.portfolio.payflowreliability.risk;

/** Same names and weights as the device app (trustgate-android {@code SignalSeverity}). */
public enum SignalSeverity {
    LOW(1),
    MEDIUM(2),
    HIGH(3);

    private final int points;

    SignalSeverity(int points) {
        this.points = points;
    }

    public int points() {
        return points;
    }
}
