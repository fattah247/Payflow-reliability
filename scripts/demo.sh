#!/usr/bin/env bash
# Quick end-to-end flow against a running app: onboard a merchant, take a payment through the provider
# simulator, refund part of it, pay the merchant out, reconcile, and show the books.
# Every run uses fresh ids, so it can be repeated without resetting the database.
set -euo pipefail

source "$(dirname "$0")/lib/http.sh"
require_tools

RUN_ID="$(date +%Y%m%d%H%M%S)-$$"

onboard_merchant "merchant-demo-$RUN_ID"
echo "merchant $MERCHANT_ID registered (API key ${MERCHANT_KEY:0:12}...)"

create_payment "ORDER-$RUN_ID" 125000
payment=$(pick id)
echo "payment $payment created: HTTP $STATUS"
create_payment "ORDER-$RUN_ID" 125000
echo "same request retried: HTTP $STATUS, Idempotent-Replayed: $REPLAYED, id $(pick id)"

start_payment "$payment"
customer_pays "$payment" 125000
wait_for_state "$payment" SUCCESS
echo "customer paid at the provider; signed webhook applied: $(pick state)"

as_merchant request POST "/api/refunds/payment-intents/$payment" \
  '{"amount": 25000, "reason": "Customer returned one item"}' "Idempotency-Key: refund-$RUN_ID"
refund=$(pick id)
wait_for_refund "$refund" COMPLETED
echo "partial refund executed by the provider: $(pick status) $(pick amount)"

as_ops request POST "/api/settlements/batches?merchantId=$MERCHANT_ID" ""
echo "settlement batch: captures $(pick captureAmount) - refunds $(pick deductionAmount) = net $(pick totalAmount)"

tx=$(provider_tx "$payment")
as_ops request POST /api/reconciliation/reports "{\"rows\": [{\"providerTransactionId\": \"$tx\", \"amount\": 125000,
  \"currency\": \"IDR\", \"providerStatus\": \"SUCCESS\", \"settlementDate\": \"$(date +%F)\", \"refundedAmount\": 25000}]}"
echo "reconciliation: $(pick rows.0.outcome)"

echo "still owed to the merchant: $(owed "$MERCHANT_ID") IDR"
as_ops request GET "/api/audit-events/verify?paymentIntentId=$payment" ""
echo "audit chain: $(pick events) events, valid=$(pick valid)"
