package id.fatarc.portfolio.payflowreliability.refund;

import java.util.UUID;

/** Published inside the transaction that created a refund or changed its status; the caller holds the payment lock. */
public record RefundChanged(UUID refundId, boolean created) {
}
