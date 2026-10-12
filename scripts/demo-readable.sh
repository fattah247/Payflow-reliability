#!/usr/bin/env bash
# Narrated walkthrough of every reliability behavior, against a running app.
# Every run uses fresh ids, so it can be repeated without resetting the database.
set -euo pipefail

source "$(dirname "$0")/lib/http.sh"
require_tools

RUN_ID="$(date +%Y%m%d%H%M%S)"
MERCHANT_ID="merchant-demo-$RUN_ID"

echo "Payflow Reliability walkthrough  (run $RUN_ID, merchant $MERCHANT_ID)"

section "1. Idempotent payment creation"
create_payment "ORDER-A-$RUN_ID" 125000
PAYMENT_A=$(pick id)
show "first request" "HTTP $STATUS  id=$PAYMENT_A"
create_payment "ORDER-A-$RUN_ID" 125000
show "same key, same payload" "HTTP $STATUS  id=$(pick id)  (replayed, no duplicate)"
request POST /api/payment-intents \
  "{\"merchantId\": \"$MERCHANT_ID\", \"merchantReference\": \"ORDER-A-$RUN_ID\", \"amount\": 1, \"currency\": \"IDR\"}" \
  "Idempotency-Key: key-ORDER-A-$RUN_ID"
show "same key, different payload" "HTTP $STATUS  $(pick code)"

section "2. Authorization then capture on one provider transaction"
request POST "/api/payment-intents/$PAYMENT_A/start" ""
TX_A="provider-tx-A-$RUN_ID"
webhook "$PAYMENT_A" "evt-A-auth-$RUN_ID" "$TX_A" AUTHORIZED 125000
show "AUTHORIZED" "HTTP $STATUS  $(pick outcome) -> $(pick paymentIntent.state)"
webhook "$PAYMENT_A" "evt-A-capture-$RUN_ID" "$TX_A" SUCCESS 125000
show "SUCCESS (same provider tx)" "HTTP $STATUS  $(pick outcome) -> $(pick paymentIntent.state)"

section "3. Provider noise is absorbed, not escalated"
webhook "$PAYMENT_A" "evt-A-capture-$RUN_ID" "$TX_A" SUCCESS 125000
show "retry of the same event" "HTTP $STATUS  $(pick outcome)"
webhook "$PAYMENT_A" "evt-A-late-auth-$RUN_ID" "$TX_A" AUTHORIZED 125000
show "late AUTHORIZED after capture" "HTTP $STATUS  $(pick outcome)"
request GET "/api/review-cases?paymentIntentId=$PAYMENT_A" ""
show "review cases opened" "$(printf '%s' "$BODY" | python3 -c 'import json,sys; print(len(json.load(sys.stdin)))')"

section "4. Wrong amount: hold, defer, decide, recover"
create_payment "ORDER-B-$RUN_ID" 60000
PAYMENT_B=$(pick id)
request POST "/api/payment-intents/$PAYMENT_B/start" ""
TX_B="provider-tx-B-$RUN_ID"
webhook "$PAYMENT_B" "evt-B-wrong-$RUN_ID" "$TX_B" SUCCESS 59000
CASE_B=$(pick reviewCaseId)
show "SUCCESS for 59000 (expected 60000)" "HTTP $STATUS  $(pick outcome) $(pick reviewReason) -> $(pick paymentIntent.state)"
webhook "$PAYMENT_B" "evt-B-right-$RUN_ID" "$TX_B" SUCCESS 60000
show "corrected event while on hold" "HTTP $STATUS  $(pick code) retryable=$(pick retryable) requestId=$(pick requestId)"
request GET "/api/review-cases/$CASE_B" ""
show "operator options" "$(pick allowedActions)"
resolve_case "$CASE_B" RELEASE_PAYMENT "Provider confirmed the first callback carried a display amount."
show "RELEASE_PAYMENT" "HTTP $STATUS  payment -> $(pick paymentState)"
webhook "$PAYMENT_B" "evt-B-right-$RUN_ID" "$TX_B" SUCCESS 60000
show "provider redelivers corrected event" "HTTP $STATUS  $(pick outcome) -> $(pick paymentIntent.state)"

section "5. Settlement"
request POST "/api/settlements/batches?merchantId=$MERCHANT_ID" ""
show "batch" "HTTP $STATUS  $(pick transactionCount) payments, $(pick totalAmount) $(pick currency)"
request POST "/api/settlements/batches?merchantId=$MERCHANT_ID" ""
show "run it again" "HTTP $STATUS  $(pick code)"

section "6. Reconciliation with per-row outcomes"
TODAY="$(date +%F)"
request POST /api/reconciliation/reports "{
  \"rows\": [
    {\"providerTransactionId\": \"$TX_A\", \"amount\": 125000, \"currency\": \"IDR\", \"providerStatus\": \"SUCCESS\", \"settlementDate\": \"$TODAY\"},
    {\"providerTransactionId\": \"$TX_B\", \"amount\": 59500,  \"currency\": \"IDR\", \"providerStatus\": \"SUCCESS\", \"settlementDate\": \"$TODAY\"}
  ]
}"
show "row 1 ($TX_A)" "$(pick rows.0.outcome)"
show "row 2 ($TX_B)" "$(pick rows.1.outcome) $(pick rows.1.mismatches)"

section "7. Refund after reconciliation needs approval"
request POST "/api/refunds/payment-intents/$PAYMENT_A" '{"amount": 1, "reason": "partial"}'
show "partial refund" "HTTP $STATUS  $(pick code)"
request POST "/api/refunds/payment-intents/$PAYMENT_A" '{"amount": 125000, "reason": "Customer returned the item"}'
REFUND_A=$(pick id)
show "full refund" "HTTP $STATUS  $(pick status)"
request GET "/api/review-cases?reason=REFUND_BLOCKED_AFTER_RECONCILIATION&paymentIntentId=$PAYMENT_A" ""
REFUND_CASE=$(pick 0.id)
show "operator options" "$(pick 0.allowedActions)"
resolve_case "$REFUND_CASE" APPROVE_REFUND "Return received at warehouse."
show "APPROVE_REFUND" "HTTP $STATUS  payment -> $(pick paymentState)"
request POST "/api/refunds/$REFUND_A/complete" ""
show "complete refund" "HTTP $STATUS  $(pick status)"
request POST "/api/refunds/$REFUND_A/complete" ""
show "complete again (retry)" "HTTP $STATUS  $(pick status)"

section "8. Case file: what is payment B waiting on?"
request GET "/api/payment-intents/$PAYMENT_B/case-file" ""
show "state" "$(pick payment.state)"
show "needs action" "$(pick attention.needsAction)"
show "waiting on" "$(pick attention.waitingOn)"
show "provider events" "$(printf '%s' "$BODY" | python3 -c 'import json,sys; print(", ".join(e["outcome"] for e in json.load(sys.stdin)["providerEvents"]))')"
show "timeline entries" "$(printf '%s' "$BODY" | python3 -c 'import json,sys; print(len(json.load(sys.stdin)["timeline"]))')"

section "9. Operations summary"
request GET /api/operations/summary ""
printf '%s' "$BODY" | python3 -c '
import decimal, json, sys
summary = json.load(sys.stdin, parse_float=decimal.Decimal)
def row(label, value):
    print("   %-36s %s" % (label, value))
states = ", ".join("%s %d" % (state, count) for state, count in summary["paymentsByState"].items() if count)
queue = summary["reviewQueue"]
reasons = ", ".join("%s %d" % (reason, count) for reason, count in queue["openByReason"].items())
row("payments by state", states or "none")
row("open review cases", "%d%s" % (queue["openCases"], " (" + reasons + ")" if reasons else ""))
row("payments on hold", queue["paymentsOnHold"])
row("refunds awaiting review / completion", "%d / %d" % (summary["refunds"]["awaitingReview"], summary["refunds"]["awaitingCompletion"]))
backlog = summary["awaitingSettlement"]
row("awaiting settlement", "none" if not backlog else "")
for line in backlog:
    row("", "%s %s: %d payments, %s" % (line["merchantId"], line["currency"], line["transactionCount"], line["totalAmount"]))'

printf '\nDone. Payment A: %s  Payment B: %s\n' "$PAYMENT_A" "$PAYMENT_B"
