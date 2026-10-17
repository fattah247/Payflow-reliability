package id.fatarc.portfolio.payflowreliability.risk;

/** What a payment turned out to be, learned after the decision. */
public enum RiskLabel {
    FRAUD,
    LEGITIMATE;

    public enum Source {
        /** A fraud chargeback from the provider. */
        CHARGEBACK,
        /** The cardholder or the merchant reported it. */
        CUSTOMER_REPORT,
        /**
         * Ground truth disclosed by the traffic generator. Only a simulation can label blocked payments: in production
         * nobody learns whether a blocked customer was honest, which is why real systems keep a small holdout.
         */
        SIMULATION_GROUND_TRUTH
    }
}
