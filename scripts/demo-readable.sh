#!/usr/bin/env bash
# Narrated walkthrough of every reliability behavior, against a running app.
# Every run uses fresh ids, so it can be repeated without resetting the database.
set -euo pipefail

source "$(dirname "$0")/lib/http.sh"
require_tools

RUN_ID="$(date +%Y%m%d%H%M%S)-$$"

echo "Payflow Reliability walkthrough  (run $RUN_ID)"

section "1. Merchant onboarding: API keys, shown once, stored hashed"
onboard_merchant "merchant-demo-$RUN_ID"
show "operator registers the merchant" "HTTP $STATUS  $MERCHANT_ID, key ${MERCHANT_KEY:0:12}... (shown once)"
anonymous request GET /api/operations/summary ""
show "call without a key" "HTTP $STATUS  $(pick code)"
as_merchant request GET /api/operations/summary ""
show "merchant key, operator endpoint" "HTTP $STATUS  $(pick code)"

section "2. Idempotent payment creation"
create_payment "ORDER-A-$RUN_ID" 125000
PAYMENT_A=$(pick id)
show "first request" "HTTP $STATUS  id=$PAYMENT_A"
create_payment "ORDER-A-$RUN_ID" 125000
show "same key, same payload" "HTTP $STATUS  Idempotent-Replayed: $REPLAYED"
as_merchant request POST /api/payment-intents \
  "{\"merchantReference\": \"ORDER-A-$RUN_ID\", \"amount\": 1, \"currency\": \"IDR\"}" "Idempotency-Key: key-ORDER-A-$RUN_ID"
show "same key, different payload" "HTTP $STATUS  $(pick code)"

section "3. The customer pays; the provider sends signed, duplicated webhooks"
start_payment "$PAYMENT_A"
customer_pays "$PAYMENT_A" 125000 '"authorizeFirst": true, "deliveries": 3'
wait_for_state "$PAYMENT_A" SUCCESS >/dev/null
as_ops request GET "/api/payment-intents/$PAYMENT_A/case-file" ""
show "AUTHORIZED + SUCCESS, 3 copies each" "$(printf '%s' "$BODY" | python3 -c 'import json,sys; print(", ".join(e["status"] + " " + e["outcome"] for e in json.load(sys.stdin)["providerEvents"]))')"
show "payment" "$(pick payment.state), ledger: $(pick ledger.0.type) journal"
body=$(webhook_body "$PAYMENT_A" "evt-forged-$RUN_ID" "tx-x" FAILED 125000)
anonymous request POST /fake-provider/webhook "$body" "X-Provider-Signature: t=$(date +%s),v1=forged"
show "forged webhook" "HTTP $STATUS  $(pick code) (nothing stored)"

section "4. Wrong amount: hold, defer, decide, recover"
create_payment "ORDER-B-$RUN_ID" 60000
PAYMENT_B=$(pick id)
start_payment "$PAYMENT_B"
TX_B="tx-B-$RUN_ID"
webhook "$PAYMENT_B" "evt-B-wrong-$RUN_ID" "$TX_B" SUCCESS 59000
CASE_B=$(pick reviewCaseId)
show "SUCCESS for 59000 (expected 60000)" "HTTP $STATUS  $(pick outcome) $(pick reviewReason) -> $(pick paymentIntent.state)"
webhook "$PAYMENT_B" "evt-B-right-$RUN_ID" "$TX_B" SUCCESS 60000
show "corrected event while on hold" "HTTP $STATUS  $(pick code) retryable=$(pick retryable)"
as_ops request GET "/api/review-cases/$CASE_B" ""
show "operator options" "$(pick allowedActions)"
resolve_case "$CASE_B" RELEASE_PAYMENT "Provider confirmed the first callback carried a display amount."
show "RELEASE_PAYMENT (ops-ana)" "HTTP $STATUS  payment -> $(pick paymentState)"
webhook "$PAYMENT_B" "evt-B-right-$RUN_ID" "$TX_B" SUCCESS 60000
show "provider redelivers corrected event" "HTTP $STATUS  $(pick outcome) -> $(pick paymentIntent.state)"

section "5. Partial refund while the provider times out"
provider_fault REFUND TIMEOUT_AFTER_COMMIT 1
as_merchant request POST "/api/refunds/payment-intents/$PAYMENT_A" \
  '{"amount": 25000, "reason": "Customer returned one item"}' "Idempotency-Key: refund-A-$RUN_ID"
REFUND_A=$(pick id)
show "refund 25000 requested" "HTTP $STATUS  $(pick status)"
wait_for_refund "$REFUND_A" COMPLETED 30
as_provider request GET "/provider-sim/v1/payments/$PAYMENT_A" ""
show "after timeout + lookup by key" "refund COMPLETED, provider refunded $(pick refundedAmount) once"
as_merchant request POST "/api/refunds/payment-intents/$PAYMENT_A" \
  '{"amount": 100001, "reason": "too much"}' "Idempotency-Key: refund-A2-$RUN_ID"
show "refund more than is left" "HTTP $STATUS  $(pick code)"

section "6. Net settlement"
as_ops request POST "/api/settlements/batches?merchantId=$MERCHANT_ID" ""
show "batch" "HTTP $STATUS  captures $(pick captureAmount) - refunds $(pick deductionAmount) = $(pick totalAmount) IDR"
as_ops request POST "/api/settlements/batches?merchantId=$MERCHANT_ID" ""
show "run it again" "HTTP $STATUS  $(pick code)"

section "7. Two-sided reconciliation"
TODAY="$(date +%F)"
TX_A=$(provider_tx "$PAYMENT_A")
as_ops request POST /api/reconciliation/reports "{\"settlementDate\": \"$TODAY\", \"merchantId\": \"$MERCHANT_ID\", \"rows\": [
  {\"providerTransactionId\": \"$TX_A\", \"amount\": 125000, \"currency\": \"IDR\", \"providerStatus\": \"SUCCESS\", \"settlementDate\": \"$TODAY\", \"refundedAmount\": 25000}
]}"
show "row 1 (payment A)" "$(pick rows.0.outcome)"
show "payment B (paid out, not reported)" "$(pick rows.1.outcome)"

section "8. Refund after reconciliation needs two operators"
as_merchant request POST "/api/refunds/payment-intents/$PAYMENT_A" \
  '{"amount": 100000, "reason": "Warranty claim"}' "Idempotency-Key: refund-A3-$RUN_ID"
REFUND_A3=$(pick id)
show "refund requested" "HTTP $STATUS  $(pick status)"
as_ops request GET "/api/review-cases?reason=REFUND_BLOCKED_AFTER_RECONCILIATION&paymentIntentId=$PAYMENT_A" ""
REFUND_CASE=$(pick 0.id)
resolve_case "$REFUND_CASE" APPROVE_REFUND "Warranty claim accepted."
show "ops-ana proposes APPROVE_REFUND" "HTTP $STATUS  $(pick status)"
as_ops request POST "/api/review-cases/$REFUND_CASE/approve" '{"note": "approving my own"}'
show "ops-ana approves own proposal" "HTTP $STATUS  $(pick code)"
as_ops2 request POST "/api/review-cases/$REFUND_CASE/approve" '{"note": "Checked the warranty terms."}'
show "ops-budi approves" "HTTP $STATUS  $(pick status)"
wait_for_refund "$REFUND_A3" COMPLETED 30
as_ops request GET "/api/payment-intents/$PAYMENT_A" ""
show "provider executes the refund" "payment $(pick state), refunded $(pick refundedAmount)"

section "9. Books and evidence"
show "owed to the merchant" "$(owed "$MERCHANT_ID") IDR (refund after payout, deducted next time)"
as_ops request GET /api/ledger/invariants ""
show "ledger invariants" "healthy=$(pick healthy), $(pick journalsChecked) journals checked"
as_ops request GET "/api/audit-events/verify?paymentIntentId=$PAYMENT_A" ""
show "payment A audit chain" "$(pick events) events, valid=$(pick valid)"
as_ops request GET "/api/payment-intents/$PAYMENT_B/case-file" ""
show "payment B waiting on" "$(pick attention.waitingOn)"

section "10. Operations summary"
as_ops request GET /api/operations/summary ""
printf '%s' "$BODY" | python3 -c '
import decimal, json, sys
summary = json.load(sys.stdin, parse_float=decimal.Decimal)
import textwrap
def row(label, value):
    for line in textwrap.wrap(str(value), 80) or [""]:
        print("   %-36s %s" % (label, line))
        label = ""
states = ", ".join("%s %d" % (state, count) for state, count in summary["paymentsByState"].items() if count)
queue = summary["reviewQueue"]
reasons = ", ".join("%s %d" % (reason, count) for reason, count in queue["unresolvedByReason"].items())
calls = summary["providerCalls"]
row("payments by state", states or "none")
row("unresolved review cases", "%d%s" % (queue["unresolved"], " (" + reasons + ")" if reasons else ""))
row("awaiting a second operator", queue["awaitingApproval"])
row("provider calls pending / unknown", "%d / %d" % (calls["pending"], calls["needsReview"]))
row("stuck payments", summary["stuckPayments"])
ledger = summary["ledger"]
row("ledger", "not checked yet" if ledger is None else ("healthy" if ledger["healthy"] else "VIOLATIONS"))'

printf '\nDone. Payment A: %s  Payment B: %s\n' "$PAYMENT_A" "$PAYMENT_B"
