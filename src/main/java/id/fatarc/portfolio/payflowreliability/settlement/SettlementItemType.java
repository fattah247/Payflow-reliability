package id.fatarc.portfolio.payflowreliability.settlement;

public enum SettlementItemType {
    /** A captured payment paid out (positive). */
    CAPTURE,
    /** A completed refund deducted (negative). */
    REFUND,
    /** A confirmed reversal deducted (negative). */
    REVERSAL
}
