package id.fatarc.portfolio.payflowreliability.risk;

/**
 * What the money buys, as the merchant declares it. Some goods are easier to turn into cash than others, so a
 * stolen card or a compromised device is worth more to an attacker there.
 */
public enum RiskAction {
    /** Shipped goods: a delivery address and time to stop it. */
    PHYSICAL_GOODS,
    /** Delivered instantly, hard to take back. */
    DIGITAL_GOODS,
    /** Gift cards, wallet top-ups: as good as cash. */
    STORED_VALUE
}
