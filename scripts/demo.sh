#!/usr/bin/env bash
# Quick end-to-end flow against a running app: onboard a merchant, take a payment through the provider
# simulator, refund part of it, pay the merchant out, reconcile against the provider's report and the bank
# statement, and show the books.
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

batch=$(settle_now)
batch_view "$batch"
echo "settlement batch: net $(pick ledger.batchTotal) paid out; provider fee $(pick provider.fee), $(pick provider.net) on its way to the bank"

tx=$(provider_tx "$payment")
as_ops request POST /api/reconciliation/reports "{\"rows\": [{\"providerTransactionId\": \"$tx\", \"amount\": 125000,
  \"currency\": \"IDR\", \"providerStatus\": \"SUCCESS\", \"settlementDate\": \"$(date +%F)\", \"refundedAmount\": 25000}]}"
echo "provider report: $(pick rows.0.outcome)"

batch_view "$batch"
bank_catch_up "$(pick provider.expectedValueDate)"
batch_view "$batch"
echo "bank statement: payout $(pick payoutStatus), $(pick bank.0.received) credited"
echo "payment: $(states "$payment") (ledger, provider and bank agree)"

echo "still owed to the merchant: $(owed "$MERCHANT_ID") IDR"
as_ops request GET "/api/audit-events/verify?paymentIntentId=$payment" ""
echo "audit chain: $(pick events) events, valid=$(pick valid)"
