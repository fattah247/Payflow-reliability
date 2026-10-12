#!/usr/bin/env bash
# Runs one reliability scenario against a running app and checks its outcome.
# The screenshots in docs/screenshots are the output of these scenarios.
#
#   ./scripts/scenarios.sh <idempotency|duplicate-webhook|amount-mismatch|settlement|reconciliation|audit-trail>
#
# Every run uses fresh ids. Exits non-zero if any check fails.
set -euo pipefail

source "$(dirname "$0")/lib/http.sh"

RUN_ID="$(date +%Y%m%d%H%M%S)-$$"
MERCHANT_ID="merchant-$RUN_ID"
WORK_DIR="$(mktemp -d)"
trap 'rm -rf "$WORK_DIR"' EXIT

header() {
  printf 'SCENARIO  %s\n' "$1"
  printf 'Goal      %s\n\n' "$2"
}

checks() {
  printf '\nChecks\n'
}

# captured REFERENCE AMOUNT  -> creates, starts and captures a payment; prints its id
captured() {
  local reference="$1" amount="$2" id
  create_payment "$reference" "$amount"
  id=$(pick id)
  request POST "/api/payment-intents/$id/start" ""
  webhook "$id" "evt-$reference" "tx-$reference" SUCCESS "$amount"
  printf '%s' "$id"
}

scenario_idempotency() {
  header "Idempotent payment creation" \
    "A retried request returns the original payment, even when retries race."

  create_payment "ORDER-$RUN_ID" 125000
  local first_id first_status
  first_id=$(pick id)
  first_status=$STATUS
  show "first request" "HTTP $STATUS  id=$first_id"

  create_payment "ORDER-$RUN_ID" 125000
  local replay_id replay_status replay_header
  replay_id=$(pick id)
  replay_status=$STATUS
  replay_header=$REPLAYED
  show "same key, same payload" "HTTP $STATUS  Idempotent-Replayed: $REPLAYED"
  show "" "id=$replay_id"

  request POST /api/payment-intents \
    "{\"merchantId\": \"$MERCHANT_ID\", \"merchantReference\": \"ORDER-$RUN_ID\", \"amount\": 999, \"currency\": \"IDR\"}" \
    "Idempotency-Key: key-ORDER-$RUN_ID"
  local changed_status changed_code
  changed_status=$STATUS
  changed_code=$(pick code)
  show "same key, different payload" "HTTP $STATUS  $changed_code"

  local i
  for i in 1 2 3 4 5 6 7 8; do
    ( create_payment "ORDER-RACE-$RUN_ID" 50000; printf '%s %s\n' "$STATUS" "$(pick id)" > "$WORK_DIR/race-$i" ) &
  done
  wait
  local statuses distinct
  statuses=$(tally "$WORK_DIR"/race-*)
  distinct=$(cut -d' ' -f2 "$WORK_DIR"/race-* | sort -u | wc -l | tr -d ' ')
  show "8 identical requests in parallel" "$statuses"
  show "" "$distinct distinct payment id"

  checks
  check "first request creates the payment (201)" "$first_status" "201"
  check "retry returns the same payment (200, replayed)" "$replay_status $replay_header $replay_id" "200 true $first_id"
  check "reused key with a changed payload is rejected" "$changed_status $changed_code" "422 IDEMPOTENCY_KEY_REUSED"
  check "parallel retries converge on one payment" "$distinct" "1"
}

scenario_duplicate_webhook() {
  header "Provider redelivers webhooks" \
    "Retries and reordering are absorbed: applied once, no duplicate rows, no review toil."

  create_payment "ORDER-$RUN_ID" 125000
  local payment
  payment=$(pick id)
  request POST "/api/payment-intents/$payment/start" ""
  local tx="tx-$RUN_ID"

  webhook "$payment" "evt-1-$RUN_ID" "$tx" SUCCESS 125000
  local first_outcome
  first_outcome=$(pick outcome)
  show "SUCCESS event" "HTTP $STATUS  $first_outcome -> $(pick paymentIntent.state)"

  webhook "$payment" "evt-1-$RUN_ID" "$tx" SUCCESS 125000
  local redelivery_outcome
  redelivery_outcome=$(pick outcome)
  show "same event again" "HTTP $STATUS  $redelivery_outcome (first: $(pick originalOutcome))"

  webhook "$payment" "evt-2-$RUN_ID" "$tx" SUCCESS 125000
  local restated_outcome
  restated_outcome=$(pick outcome)
  show "same status, new event id" "HTTP $STATUS  $restated_outcome"

  webhook "$payment" "evt-3-$RUN_ID" "$tx" AUTHORIZED 125000
  local stale_outcome
  stale_outcome=$(pick outcome)
  show "AUTHORIZED after capture" "HTTP $STATUS  $stale_outcome"

  create_payment "ORDER-BURST-$RUN_ID" 40000
  local burst
  burst=$(pick id)
  request POST "/api/payment-intents/$burst/start" ""
  local i
  for i in 1 2 3 4 5 6 7 8 9 10; do
    ( webhook "$burst" "evt-burst-$RUN_ID" "tx-burst-$RUN_ID" SUCCESS 40000
      printf '%s %s\n' "$STATUS" "$(pick outcome)" > "$WORK_DIR/burst-$i" ) &
  done
  wait
  local burst_statuses applied
  burst_statuses=$(tally "$WORK_DIR"/burst-*)
  applied=$(grep -c ' APPLIED$' "$WORK_DIR"/burst-* | awk -F: '{s+=$2} END {print s}')
  show "10 deliveries in parallel" "$burst_statuses"
  show "" "$applied APPLIED, $((10 - applied)) DUPLICATE_IGNORED"

  request GET "/api/payment-intents/$payment/case-file" ""
  local transactions
  transactions=$(printf '%s' "$BODY" | python3 -c 'import json,sys; print(len(json.load(sys.stdin)["providerTransactions"]))')
  request GET "/api/review-cases?paymentIntentId=$payment" ""
  local cases
  cases=$(size)
  show "provider transactions recorded" "$transactions"
  show "review cases opened" "$cases"

  checks
  check "first delivery is applied" "$first_outcome" "APPLIED"
  check "exact redelivery is ignored" "$redelivery_outcome" "DUPLICATE_IGNORED"
  check "restated status under a new id is ignored" "$restated_outcome" "DUPLICATE_IGNORED"
  check "late authorization is stale, not a contradiction" "$stale_outcome" "STALE_IGNORED"
  check "parallel deliveries apply exactly once" "$applied" "1"
  check "one provider transaction, no review cases" "$transactions $cases" "1 0"
}

scenario_amount_mismatch() {
  header "Provider reports the wrong amount" \
    "Hold the payment, keep the provider retrying, let an operator decide, then recover."

  create_payment "ORDER-$RUN_ID" 60000
  local payment
  payment=$(pick id)
  request POST "/api/payment-intents/$payment/start" ""
  local tx="tx-$RUN_ID"

  webhook "$payment" "evt-wrong-$RUN_ID" "$tx" SUCCESS 59000
  local hold_status hold_state held_from case_id
  hold_status=$STATUS
  hold_state=$(pick paymentIntent.state)
  held_from=$(pick paymentIntent.stateBeforeReview)
  case_id=$(pick reviewCaseId)
  show "SUCCESS for 59000 (expected 60000)" "HTTP $STATUS  $(pick outcome) $(pick reviewReason)"
  show "" "payment $hold_state, held from $held_from"

  webhook "$payment" "evt-right-$RUN_ID" "$tx" SUCCESS 60000
  local deferred_status deferred_code
  deferred_status=$STATUS
  deferred_code=$(pick code)
  show "corrected event while held" "HTTP $STATUS  $deferred_code retryable=$(pick retryable)"

  request GET "/api/review-cases/$case_id" ""
  show "review case options" "$(pick allowedActions)"

  resolve_case "$case_id" ACKNOWLEDGE "Looks fine"
  local acknowledge_status
  acknowledge_status=$STATUS
  show "ACKNOWLEDGE without a decision" "HTTP $STATUS  $(pick code)"

  resolve_case "$case_id" RELEASE_PAYMENT "Provider confirmed a display bug"
  local released_state
  released_state=$(pick paymentState)
  show "RELEASE_PAYMENT" "HTTP $STATUS  payment -> $released_state"

  webhook "$payment" "evt-right-$RUN_ID" "$tx" SUCCESS 60000
  local final_outcome final_state
  final_outcome=$(pick outcome)
  final_state=$(pick paymentIntent.state)
  show "provider redelivers corrected event" "HTTP $STATUS  $final_outcome -> $final_state"

  checks
  check "mismatch holds the in-flight payment (202)" "$hold_status $hold_state $held_from" "202 MANUAL_REVIEW PENDING"
  check "events wait while held (409, retryable)" "$deferred_status $deferred_code" "409 PAYMENT_ON_HOLD"
  check "a held payment needs a decision" "$acknowledge_status" "422"
  check "release returns it to where it was held" "$released_state" "PENDING"
  check "the redelivered event is applied" "$final_outcome $final_state" "APPLIED SUCCESS"
}

scenario_settlement() {
  header "Settlement batch" \
    "Settle only eligible payments, once, even when runs race and payments move on."

  local first second refunded
  first=$(captured "ORDER-A-$RUN_ID" 1000)
  second=$(captured "ORDER-B-$RUN_ID" 2000)
  refunded=$(captured "ORDER-C-$RUN_ID" 3000)
  show "captured payments" "1000 + 2000 + 3000 IDR"
  request POST "/api/refunds/payment-intents/$refunded" '{"amount": 3000, "reason": "customer cancelled"}'
  show "refund requested on the 3000 one" "HTTP $STATUS  $(pick status)"

  local i
  for i in 1 2 3 4; do
    ( request POST "/api/settlements/batches?merchantId=$MERCHANT_ID" ""
      if [ "$STATUS" = "201" ]; then printf '%s created\n' "$STATUS"; else printf '%s %s\n' "$STATUS" "$(pick code)"; fi \
        > "$WORK_DIR/settle-$i" ) &
  done
  wait
  show "4 settlement runs in parallel" ""
  sort "$WORK_DIR"/settle-* | uniq -c | while read -r count status detail; do
    show "" "$count x HTTP $status $detail"
  done

  request GET "/api/settlements/batches?merchantId=$MERCHANT_ID" ""
  local batches count total
  batches=$(size)
  count=$(pick 0.transactionCount)
  total=$(pick 0.totalAmount)
  show "batches for merchant" "$batches: $count payments, $total $(pick 0.currency)"

  request GET "/api/payment-intents/$refunded" ""
  local refunded_state
  refunded_state=$(pick state)
  show "refunded payment" "$refunded_state (skipped)"

  request POST "/api/settlements/batches?merchantId=$MERCHANT_ID" ""
  local rerun
  rerun="$STATUS $(pick code)"
  show "run again" "HTTP $rerun"

  checks
  check "exactly one batch from four racing runs" "$batches" "1"
  check "only eligible payments are paid out" "$count $total" "2 3000.00"
  check "a payment that moved on is skipped, not failed" "$refunded_state" "REFUND_REQUESTED"
  check "nothing is settled twice" "$rerun" "409 NOTHING_TO_SETTLE"
}

scenario_reconciliation() {
  header "Reconciliation report" \
    "Every row gets a verdict; only clean matches change state; re-import is safe."

  local matched mismatched
  matched=$(captured "ORDER-A-$RUN_ID" 1000)
  mismatched=$(captured "ORDER-B-$RUN_ID" 2000)
  request POST "/api/settlements/batches?merchantId=$MERCHANT_ID" ""
  local today
  today=$(date +%F)
  local report="{\"rows\": [
    {\"providerTransactionId\": \"tx-ORDER-A-$RUN_ID\", \"amount\": 1000, \"currency\": \"IDR\", \"providerStatus\": \"SUCCESS\", \"settlementDate\": \"$today\"},
    {\"providerTransactionId\": \"tx-ORDER-B-$RUN_ID\", \"amount\": 1950, \"currency\": \"IDR\", \"providerStatus\": \"SUCCESS\", \"settlementDate\": \"$today\"},
    {\"providerTransactionId\": \"tx-UNKNOWN-$RUN_ID\", \"amount\": 500, \"currency\": \"IDR\", \"providerStatus\": \"SUCCESS\", \"settlementDate\": \"$today\"},
    {\"providerTransactionId\": \"tx-ORDER-A-$RUN_ID\", \"amount\": 1000, \"currency\": \"IDR\", \"providerStatus\": \"SUCCESS\", \"settlementDate\": \"$today\"}
  ]}"

  request GET "/api/review-cases?status=OPEN" ""
  local cases_before
  cases_before=$(size)

  request POST /api/reconciliation/reports "$report"
  printf '   first import (4 rows)\n'
  local first_outcomes
  first_outcomes=$(printf '%s' "$BODY" | python3 -c '
import json, sys
report = json.load(sys.stdin)
for row in report["rows"]:
    reasons = "  " + ", ".join(row["mismatches"]) if row["mismatches"] else ""
    reference = row["providerTransactionId"].replace("-" + sys.argv[1], "")
    print("     row %d  %-12s %-19s%s" % (row["rowNumber"], reference, row["outcome"], reasons), file=sys.stderr)
print(" ".join(row["outcome"] for row in report["rows"]))' "$RUN_ID")
  printf '     reconciled %s, mismatches %s\n' "$(pick reconciledCount)" "$(pick mismatchCount)"

  request GET "/api/review-cases?status=OPEN" ""
  local opened_by_first
  opened_by_first=$(( $(size) - cases_before ))

  request POST /api/reconciliation/reports "$report"
  local second_outcomes
  second_outcomes=$(printf '%s' "$BODY" | python3 -c 'import json,sys; print(" ".join(r["outcome"] for r in json.load(sys.stdin)["rows"]))')
  printf '   same report imported again\n'
  printf '     %s\n' "$second_outcomes"
  request GET "/api/review-cases?status=OPEN" ""
  local opened_by_second
  opened_by_second=$(( $(size) - cases_before - opened_by_first ))
  show "review cases opened" "$opened_by_first by first import, $opened_by_second by second"

  request GET "/api/payment-intents/$matched" ""
  local matched_state
  matched_state=$(pick state)
  request GET "/api/payment-intents/$mismatched" ""
  local mismatched_state
  mismatched_state=$(pick state)
  show "payment states" "matched $matched_state, mismatched $mismatched_state"

  checks
  check "each row gets its own verdict" "$first_outcomes" "RECONCILED MISMATCH NOT_FOUND_LOCALLY DUPLICATE_ROW"
  check "only the clean match is reconciled" "$matched_state $mismatched_state" "RECONCILED SETTLED"
  check "re-import is safe" "$second_outcomes" "ALREADY_RECONCILED MISMATCH NOT_FOUND_LOCALLY DUPLICATE_ROW"
  check "re-import opens no new review cases" "$opened_by_first $opened_by_second" "3 0"
}

scenario_audit_trail() {
  header "Audit trail and case file" \
    "One call explains what happened to a payment, who did it, and which request caused it."

  create_payment "ORDER-$RUN_ID" 60000 "X-Request-Id: checkout-1"
  local payment
  payment=$(pick id)
  create_payment "ORDER-$RUN_ID" 60000 "X-Request-Id: checkout-1-retry"
  request POST "/api/payment-intents/$payment/start" "" "X-Request-Id: checkout-start"
  webhook "$payment" "evt-a-$RUN_ID" "tx-$RUN_ID" SUCCESS 59000 "X-Request-Id: psp-delivery-1"
  local case_id
  case_id=$(pick reviewCaseId)
  webhook "$payment" "evt-b-$RUN_ID" "tx-$RUN_ID" SUCCESS 60000 "X-Request-Id: psp-delivery-2"
  resolve_case "$case_id" RELEASE_PAYMENT "Provider confirmed a display bug" "X-Request-Id: ops-review-17"
  webhook "$payment" "evt-b-$RUN_ID" "tx-$RUN_ID" SUCCESS 60000 "X-Request-Id: psp-delivery-3"
  request POST "/api/settlements/batches?merchantId=$MERCHANT_ID" "" "X-Request-Id: settlement-run"

  request GET "/api/payment-intents/$payment/case-file" ""
  printf '%s' "$BODY" > "$WORK_DIR/case-file.json"
  python3 - "$WORK_DIR/case-file.json" <<'PY'
import decimal, json, sys
case = json.load(open(sys.argv[1]), parse_float=decimal.Decimal)
attention = case["attention"]
print("   payment     %s  %s %s  %s" % (case["payment"]["id"], case["payment"]["amount"],
                                         case["payment"]["currency"], case["payment"]["state"]))
print("   waiting on  %s" % attention["waitingOn"])
print("   needs action %s" % ("yes" if attention["needsAction"] else "no"))
print()
print("   %-12s %-26s %-24s %-15s %s" % ("TIME (UTC)", "EVENT", "TRANSITION", "ACTOR", "REQUEST"))
for event in case["timeline"]:
    transition = ""
    if event["previousState"]:
        transition = "%s -> %s" % (event["previousState"], event["newState"])
    print("   %-12s %-26s %-24s %-15s %s" % (event["createdAt"][11:23], event["eventType"][:26],
                                             transition, event["actor"][:15], event["requestId"] or ""))
print()
print("   provider events: %s" % ", ".join(e["outcome"] for e in case["providerEvents"]))
print("   review cases:    %s" % ", ".join("%s (%s, %s)" % (c["reason"], c["status"], c["resolutionAction"])
                                         for c in case["reviewCases"]))
PY

  local timeline_requests
  timeline_requests=$(python3 -c '
import json, sys
case = json.load(open(sys.argv[1]))
print(" ".join(sorted({e["requestId"] for e in case["timeline"]})))' "$WORK_DIR/case-file.json")
  local state
  state=$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1]))["payment"]["state"])' "$WORK_DIR/case-file.json")

  checks
  check "every step is traceable to its request" "$timeline_requests" \
    "checkout-1 checkout-1-retry checkout-start ops-review-17 psp-delivery-1 psp-delivery-2 psp-delivery-3 settlement-run"
  check "the payment ends settled" "$state" "SETTLED"
}

usage() {
  echo "usage: $0 <idempotency|duplicate-webhook|amount-mismatch|settlement|reconciliation|audit-trail>" >&2
}

require_tools
case "${1:-}" in
  idempotency) scenario_idempotency ;;
  duplicate-webhook) scenario_duplicate_webhook ;;
  amount-mismatch) scenario_amount_mismatch ;;
  settlement) scenario_settlement ;;
  reconciliation) scenario_reconciliation ;;
  audit-trail) scenario_audit_trail ;;
  *) usage; exit 2 ;;
esac

if [ "$CHECK_FAILURES" -gt 0 ]; then
  printf '\n%s check(s) failed\n' "$CHECK_FAILURES"
  exit 1
fi
printf '\nAll checks passed\n'
