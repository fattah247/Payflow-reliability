#!/usr/bin/env bash
# Runs one reliability scenario against a running app and checks its outcome.
# The screenshots in docs/screenshots are the output of these scenarios.
#
#   ./scripts/scenarios.sh <scenario>
#
# Scenarios: idempotency, duplicate-webhook, amount-mismatch, provider-outage, lost-webhook, settlement,
#            reconciliation, four-eyes, security, audit-trail
#
# Every run registers a fresh merchant and uses fresh ids. Exits non-zero if any check fails.
set -euo pipefail

source "$(dirname "$0")/lib/http.sh"

RUN_ID="$(date +%Y%m%d%H%M%S)-$$"
WORK_DIR="$(mktemp -d)"
trap 'rm -rf "$WORK_DIR"' EXIT

header() {
  printf 'SCENARIO  %s\n' "$1"
  printf 'Goal      %s\n\n' "$2"
}

checks() {
  printf '\nChecks\n'
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

  as_merchant request POST /api/payment-intents \
    "{\"merchantReference\": \"ORDER-$RUN_ID\", \"amount\": 999, \"currency\": \"IDR\"}" \
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
  start_payment "$payment"
  local tx="tx-$RUN_ID"

  webhook "$payment" "evt-1-$RUN_ID" "$tx" SUCCESS 125000
  local first_outcome
  first_outcome=$(pick outcome)
  show "signed SUCCESS event" "HTTP $STATUS  $first_outcome -> $(pick paymentIntent.state)"

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
  start_payment "$burst"
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

  as_ops request GET "/api/payment-intents/$payment/case-file" ""
  local transactions journals
  transactions=$(printf '%s' "$BODY" | python3 -c 'import json,sys; print(len(json.load(sys.stdin)["providerTransactions"]))')
  journals=$(printf '%s' "$BODY" | python3 -c 'import json,sys; print(len(json.load(sys.stdin)["ledger"]))')
  as_ops request GET "/api/review-cases?paymentIntentId=$payment" ""
  local cases
  cases=$(size)
  show "provider transactions recorded" "$transactions"
  show "ledger journals posted" "$journals"
  show "review cases opened" "$cases"

  checks
  check "first delivery is applied" "$first_outcome" "APPLIED"
  check "exact redelivery is ignored" "$redelivery_outcome" "DUPLICATE_IGNORED"
  check "restated status under a new id is ignored" "$restated_outcome" "DUPLICATE_IGNORED"
  check "late authorization is stale, not a contradiction" "$stale_outcome" "STALE_IGNORED"
  check "parallel deliveries apply exactly once" "$applied" "1"
  check "one transaction, one capture journal, no review cases" "$transactions $journals $cases" "1 1 0"
}

scenario_amount_mismatch() {
  header "Provider reports the wrong amount" \
    "Hold the payment, keep the provider retrying, let an operator decide, then recover."

  create_payment "ORDER-$RUN_ID" 60000
  local payment
  payment=$(pick id)
  start_payment "$payment"
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

  as_ops request GET "/api/review-cases/$case_id" ""
  show "review case options" "$(pick allowedActions)"

  resolve_case "$case_id" ACKNOWLEDGE "Looks fine"
  local acknowledge_status
  acknowledge_status=$STATUS
  show "ACKNOWLEDGE without a decision" "HTTP $STATUS  $(pick code)"

  resolve_case "$case_id" RELEASE_PAYMENT "Provider confirmed a display bug"
  local released_state
  released_state=$(pick paymentState)
  show "RELEASE_PAYMENT (ops-ana)" "HTTP $STATUS  payment -> $released_state"

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

scenario_provider_outage() {
  header "Provider times out after refunding" \
    "An unknown outcome is resolved by asking the provider, so the customer is refunded exactly once."

  local payment
  payment=$(paid "ORDER-$RUN_ID" 125000)
  show "payment captured via the provider" "125000 IDR"

  provider_fault REFUND TIMEOUT_AFTER_COMMIT 1
  show "provider fault injected" "refund commits, answer arrives after our timeout"
  as_merchant request POST "/api/refunds/payment-intents/$payment" \
    '{"amount": 40000, "reason": "Customer returned one item"}' "Idempotency-Key: refund-$RUN_ID"
  local refund refund_status
  refund=$(pick id)
  refund_status=$STATUS
  show "partial refund requested" "HTTP $STATUS  $(pick status) 40000"

  run_outbox
  as_ops request GET "/api/refunds/$refund" ""
  local after_timeout
  after_timeout=$(pick status)
  as_ops request GET "/api/payment-intents/$payment/case-file" ""
  show "first provider call" "timed out -> refund still $after_timeout"
  show "" "recorded as: $(pick providerCalls.0.lastError | cut -d: -f1) (unknown outcome)"

  wait_for_refund "$refund" COMPLETED 20 || true
  local completed
  completed=$(pick status)
  show "next attempt looks up the key" "refund $completed"

  as_provider request GET "/provider-sim/v1/payments/$payment" ""
  local provider_refunded
  provider_refunded=$(pick refundedAmount)
  as_ops request GET "/api/payment-intents/$payment/case-file" ""
  local attempts our_refunded journals
  attempts=$(pick providerCalls.0.attempts)
  our_refunded=$(pick payment.refundedAmount)
  journals=$(printf '%s' "$BODY" | python3 -c 'import json,sys; print(" ".join(j["type"] for j in json.load(sys.stdin)["ledger"]))')
  show "provider refunded" "$provider_refunded IDR"
  show "we recorded" "$our_refunded IDR after $attempts attempts"
  show "ledger" "$journals"

  checks
  check "refund accepted for asynchronous execution (201)" "$refund_status" "201"
  check "unknown outcome is not treated as failure" "$after_timeout" "REQUESTED"
  check "the refund completes" "$completed" "COMPLETED"
  check "the customer was refunded exactly once" "$provider_refunded $our_refunded" "40000.00 40000.00"
  check "books show the capture and one refund" "$journals" "CAPTURE REFUND"
}

scenario_lost_webhook() {
  header "Provider webhook is lost" \
    "The status sweeper asks the provider about stuck payments and applies the answer like a webhook."

  create_payment "ORDER-$RUN_ID" 80000
  local payment
  payment=$(pick id)
  start_payment "$payment"
  customer_pays "$payment" 80000 '"deliverWebhook": false'
  show "customer paid at the provider" "webhook dropped"
  sleep 1
  as_ops request GET "/api/payment-intents/$payment" ""
  local before
  before=$(pick state)
  show "our view of the payment" "$before"

  as_ops request POST "/api/operations/sweeps/payment-status?olderThanSeconds=0" ""
  show "status sweep" "HTTP $STATUS  checked $(pick checked), recovered $(pick recovered)"

  as_ops request GET "/api/payment-intents/$payment/case-file" ""
  local after source outcome journals
  after=$(pick payment.state)
  source=$(pick providerEvents.0.source)
  outcome=$(pick providerEvents.0.outcome)
  journals=$(printf '%s' "$BODY" | python3 -c 'import json,sys; print(len(json.load(sys.stdin)["ledger"]))')
  show "after the sweep" "$after (event source $source, $outcome)"

  checks
  check "without the webhook the payment looks pending" "$before" "PENDING"
  check "the sweeper recovers the capture" "$after $source $outcome" "SUCCESS POLL APPLIED"
  check "the capture is booked once" "$journals" "1"
}

scenario_settlement() {
  header "Net settlement" \
    "Pay out captures minus refunds, once, even when runs race; held payments wait."

  local first second refunded held
  first=$(paid "ORDER-A-$RUN_ID" 1000)
  second=$(paid "ORDER-B-$RUN_ID" 2000)
  refunded=$(paid "ORDER-C-$RUN_ID" 3000)
  show "captured through the provider" "1000 + 2000 + 3000 IDR"
  as_merchant request POST "/api/refunds/payment-intents/$refunded" \
    '{"amount": 500, "reason": "late delivery discount"}' "Idempotency-Key: refund-$RUN_ID"
  wait_for_refund "$(pick id)" COMPLETED || true
  show "partial refund on the 3000 one" "$(pick status) 500"

  create_payment "ORDER-D-$RUN_ID" 4000
  held=$(pick id)
  start_payment "$held"
  webhook "$held" "evt-held-$RUN_ID" "tx-held-$RUN_ID" SUCCESS 4100
  show "payment with a wrong amount" "$(pick paymentIntent.state) (held)"

  local i
  for i in 1 2 3 4; do
    ( as_ops request POST "/api/settlements/batches?merchantId=$MERCHANT_ID" ""
      if [ "$STATUS" = "201" ]; then printf '%s created\n' "$STATUS"; else printf '%s %s\n' "$STATUS" "$(pick code)"; fi \
        > "$WORK_DIR/settle-$i" ) &
  done
  wait
  show "4 settlement runs in parallel" ""
  sort "$WORK_DIR"/settle-* | uniq -c | while read -r count status detail; do
    show "" "$count x HTTP $status $detail"
  done

  as_ops request GET "/api/settlements/batches?merchantId=$MERCHANT_ID" ""
  local batches captures deductions net
  batches=$(size)
  captures=$(pick 0.captureAmount)
  deductions=$(pick 0.deductionAmount)
  net=$(pick 0.totalAmount)
  show "batch" "captures $captures - refunds $deductions = net $net IDR"

  as_ops request GET "/api/payment-intents/$held" ""
  local held_state
  held_state=$(pick state)
  show "held payment" "$held_state (waits for a decision)"
  local balance
  balance=$(owed "$MERCHANT_ID")
  show "ledger: still owed to merchant" "$balance IDR"

  as_ops request POST "/api/settlements/batches?merchantId=$MERCHANT_ID" ""
  local rerun
  rerun="$STATUS $(pick code)"
  show "run again" "HTTP $rerun"

  checks
  check "exactly one batch from four racing runs" "$batches" "1"
  check "refunds are deducted from the payout" "$captures $deductions $net" "6000.00 500.00 5500.00"
  check "a held payment is skipped, not failed" "$held_state" "MANUAL_REVIEW"
  check "after payout the ledger owes nothing" "$balance" "0.00"
  check "nothing is settled twice" "$rerun" "409 NOTHING_TO_SETTLE"
}

scenario_reconciliation() {
  header "Two-sided reconciliation" \
    "Every reported row gets a verdict, and payouts the provider did not report are flagged."

  local matched mismatched unreported
  matched=$(paid "ORDER-A-$RUN_ID" 1000)
  mismatched=$(paid "ORDER-B-$RUN_ID" 2000)
  unreported=$(paid "ORDER-C-$RUN_ID" 3000)
  as_ops request POST "/api/settlements/batches?merchantId=$MERCHANT_ID" ""
  local tx_a tx_b tx_c today
  tx_a=$(provider_tx "$matched")
  tx_b=$(provider_tx "$mismatched")
  tx_c=$(provider_tx "$unreported")
  today=$(date +%F)
  local report="{\"settlementDate\": \"$today\", \"merchantId\": \"$MERCHANT_ID\", \"rows\": [
    {\"providerTransactionId\": \"$tx_a\", \"amount\": 1000, \"currency\": \"IDR\", \"providerStatus\": \"SUCCESS\", \"settlementDate\": \"$today\"},
    {\"providerTransactionId\": \"$tx_b\", \"amount\": 1950, \"currency\": \"IDR\", \"providerStatus\": \"SUCCESS\", \"settlementDate\": \"$today\"},
    {\"providerTransactionId\": \"tx-unknown-$RUN_ID\", \"amount\": 500, \"currency\": \"IDR\", \"providerStatus\": \"SUCCESS\", \"settlementDate\": \"$today\"},
    {\"providerTransactionId\": \"$tx_a\", \"amount\": 1000, \"currency\": \"IDR\", \"providerStatus\": \"SUCCESS\", \"settlementDate\": \"$today\"}
  ]}"
  local ours="$tx_a $tx_b $tx_c tx-unknown-$RUN_ID"

  # The report covers this merchant's account for today, so only its payouts are expected in it.
  outcomes() {
    printf '%s' "$BODY" | python3 -c '
import json, sys
ours = set(sys.argv[1].split())
names = dict(zip(sys.argv[1].split(), ["A", "B", "C", "unknown"]))
rows = [r for r in json.load(sys.stdin)["rows"] if r["providerTransactionId"] in ours]
for r in rows:
    detail = "  " + ", ".join(r["mismatches"]) if r["outcome"] == "MISMATCH" else ""
    label = "row %s" % r["rowNumber"] if r["rowNumber"] else "not in report"
    print("     %-14s payment %-8s %-20s%s" % (label, names[r["providerTransactionId"]], r["outcome"], detail), file=sys.stderr)
print(" ".join(r["outcome"] for r in rows))' "$ours"
  }

  as_ops request GET "/api/review-cases" ""
  local cases_before
  cases_before=$(size)

  as_ops request POST /api/reconciliation/reports "$report"
  printf '   first import (4 rows for %s)\n' "$today"
  local first_outcomes
  first_outcomes=$(outcomes)

  as_ops request GET "/api/review-cases" ""
  local opened_by_first
  opened_by_first=$(( $(size) - cases_before ))

  as_ops request POST /api/reconciliation/reports "$report"
  printf '   same report imported again\n'
  local second_outcomes
  second_outcomes=$(outcomes 2>/dev/null)
  printf '     %s\n' "$second_outcomes"
  as_ops request GET "/api/review-cases" ""
  local opened_by_second
  opened_by_second=$(( $(size) - cases_before - opened_by_first ))
  show "review cases opened" "$opened_by_first by first import, $opened_by_second by second"

  local states=""
  local payment
  for payment in "$matched" "$mismatched" "$unreported"; do
    as_ops request GET "/api/payment-intents/$payment" ""
    states="$states $(pick state)"
  done
  show "payment states (A B C)" "${states# }"

  checks
  check "each row gets its own verdict" "$first_outcomes" \
    "RECONCILED MISMATCH NOT_FOUND_LOCALLY DUPLICATE_ROW MISSING_FROM_REPORT"
  check "a payout the provider did not report is flagged" "$(printf '%s' "$first_outcomes" | grep -c MISSING_FROM_REPORT)" "1"
  check "only the clean match is reconciled" "${states# }" "RECONCILED SETTLED SETTLED"
  check "re-import is safe" "$second_outcomes" \
    "ALREADY_RECONCILED MISMATCH NOT_FOUND_LOCALLY DUPLICATE_ROW MISSING_FROM_REPORT"
  check "re-import opens no new review cases" "$opened_by_first $opened_by_second" "4 0"
}

scenario_four_eyes() {
  header "Chargeback after payout needs two operators" \
    "Money-moving decisions are proposed by one operator and approved by another."

  local payment
  payment=$(paid "ORDER-$RUN_ID" 90000)
  as_ops request POST "/api/settlements/batches?merchantId=$MERCHANT_ID" ""
  show "payment captured and paid out" "90000 IDR, batch net $(pick totalAmount)"

  customer_action "$payment" chargeback
  wait_for_state "$payment" MANUAL_REVIEW >/dev/null || true
  local held_state held_from
  held_state=$(pick state)
  held_from=$(pick stateBeforeReview)
  show "provider asks for the money back" "payment $held_state (held from $held_from)"

  as_ops request GET "/api/review-cases?paymentIntentId=$payment" ""
  local case_id
  case_id=$(pick 0.id)
  show "options / need a second operator" "$(pick 0.allowedActions) / $(pick 0.approvalRequiredFor)"

  resolve_case "$case_id" REQUIRE_REVERSAL "Chargeback evidence is valid"
  local proposed_status proposed
  proposed_status=$STATUS
  proposed=$(pick status)
  show "ops-ana proposes REQUIRE_REVERSAL" "HTTP $STATUS  $proposed"

  as_ops request POST "/api/review-cases/$case_id/approve" '{"note": "approving my own proposal"}'
  local self_status self_code
  self_status=$STATUS
  self_code=$(pick code)
  show "ops-ana approves own proposal" "HTTP $STATUS  $self_code"

  as_ops2 request POST "/api/review-cases/$case_id/approve" '{"note": "Evidence checked against the provider dashboard"}'
  local approved_status approved_by
  approved_status=$STATUS
  approved_by=$(pick approvedBy)
  show "ops-budi approves" "HTTP $STATUS  approved by $approved_by -> $(pick paymentState)"

  wait_for_state "$payment" REVERSED >/dev/null || true
  local final reversed
  final=$(pick state)
  reversed=$(pick reversedAmount)
  show "provider executes the reversal" "$final, $reversed IDR returned"
  local balance
  balance=$(owed "$MERCHANT_ID")
  show "ledger: owed to merchant" "$balance IDR (deducted from the next payout)"

  checks
  check "a contradiction after payout holds the payment" "$held_state $held_from" "MANUAL_REVIEW SETTLED"
  check "the decision waits for a second operator (202)" "$proposed_status $proposed" "202 AWAITING_APPROVAL"
  check "the proposer cannot approve" "$self_status $self_code" "403 FOUR_EYES_REQUIRED"
  check "a second operator approves" "$approved_status $approved_by" "200 operator:ops-budi"
  check "the reversal executes and is booked" "$final $reversed $balance" "REVERSED 90000.00 -90000.00"
}

scenario_security() {
  header "Authentication and isolation" \
    "Every call is authenticated; merchants see only their own payments; webhooks must be signed and fresh."

  create_payment "ORDER-$RUN_ID" 10000
  local payment
  payment=$(pick id)
  local key_a="$MERCHANT_KEY"

  anonymous request GET "/api/payment-intents/$payment" ""
  local no_key="$STATUS $(pick code)"
  show "no API key" "HTTP $no_key"

  onboard_merchant "merchant-other-$RUN_ID"
  as_merchant request GET "/api/payment-intents/$payment" ""
  local other_merchant="$STATUS $(pick code)"
  show "another merchant reads the payment" "HTTP $other_merchant"
  as_merchant request GET /api/operations/summary ""
  local merchant_ops="$STATUS $(pick code)"
  show "merchant calls an operator endpoint" "HTTP $merchant_ops"
  MERCHANT_KEY="$key_a"

  as_ops request POST /api/payment-intents '{"merchantReference": "x", "amount": 1, "currency": "IDR"}' "Idempotency-Key: ops-$RUN_ID"
  local ops_create="$STATUS"
  show "operator tries to create a payment" "HTTP $ops_create"

  start_payment "$payment"
  local body
  body=$(webhook_body "$payment" "evt-$RUN_ID" "tx-$RUN_ID" SUCCESS 10000)
  anonymous request POST /fake-provider/webhook "$body" "X-Provider-Signature: t=$(date +%s),v1=deadbeef"
  local forged="$STATUS $(pick code)"
  show "webhook with a forged signature" "HTTP $forged"
  anonymous request POST /fake-provider/webhook "$body" "X-Provider-Signature: $(sign "$body" $(( $(date +%s) - 600 )))"
  local replayed="$STATUS"
  show "correctly signed, 10 minutes old" "HTTP $replayed (outside the replay window)"
  webhook "$payment" "evt-$RUN_ID" "tx-$RUN_ID" SUCCESS 10000
  local genuine="$STATUS $(pick outcome)"
  show "correctly signed, fresh" "HTTP $genuine"

  checks
  check "no key, no access" "$no_key" "401 UNAUTHENTICATED"
  check "merchants cannot see each other's payments" "$other_merchant" "404 PAYMENT_INTENT_NOT_FOUND"
  check "merchants cannot call operator endpoints" "$merchant_ops" "403 FORBIDDEN"
  check "operators cannot create payments" "$ops_create" "403"
  check "forged and replayed webhooks are rejected" "$forged $replayed" "401 INVALID_SIGNATURE 401"
  check "a genuine webhook is applied" "$genuine" "200 APPLIED"
}

scenario_audit_trail() {
  header "Audit trail and case file" \
    "One call explains what happened, who did it, which request caused it, and proves nothing was altered."

  create_payment "ORDER-$RUN_ID" 60000 "X-Request-Id: checkout-1"
  local payment
  payment=$(pick id)
  create_payment "ORDER-$RUN_ID" 60000 "X-Request-Id: checkout-1-retry"
  start_payment "$payment" "X-Request-Id: checkout-start"
  webhook "$payment" "evt-a-$RUN_ID" "tx-$RUN_ID" SUCCESS 59000 "X-Request-Id: psp-delivery-1"
  local case_id
  case_id=$(pick reviewCaseId)
  webhook "$payment" "evt-b-$RUN_ID" "tx-$RUN_ID" SUCCESS 60000 "X-Request-Id: psp-delivery-2"
  resolve_case "$case_id" RELEASE_PAYMENT "Provider confirmed a display bug" "X-Request-Id: ops-review-17"
  webhook "$payment" "evt-b-$RUN_ID" "tx-$RUN_ID" SUCCESS 60000 "X-Request-Id: psp-delivery-3"
  as_ops request POST "/api/settlements/batches?merchantId=$MERCHANT_ID" "" "X-Request-Id: settlement-run"

  as_ops request GET "/api/payment-intents/$payment/case-file" ""
  printf '%s' "$BODY" > "$WORK_DIR/case-file.json"
  python3 - "$WORK_DIR/case-file.json" <<'PY'
import decimal, json, sys
case = json.load(open(sys.argv[1]), parse_float=decimal.Decimal)
attention = case["attention"]
print("   payment     %s  %s %s  %s" % (case["payment"]["id"], case["payment"]["amount"],
                                         case["payment"]["currency"], case["payment"]["state"]))
print("   waiting on  %s" % attention["waitingOn"])
print()
print("   %-3s %-12s %-26s %-25s %-17s %s" % ("#", "TIME (UTC)", "EVENT", "TRANSITION", "ACTOR", "REQUEST"))
for event in case["timeline"]:
    transition = ""
    if event["previousState"]:
        transition = "%s -> %s" % (event["previousState"], event["newState"])
    actor = "merchant" if event["actor"].startswith("merchant:") else event["actor"]
    print("   %-3s %-12s %-26s %-25s %-17s %s" % (event["sequenceNumber"], event["createdAt"][11:23], event["eventType"][:26],
                                                  transition, actor, event["requestId"] or ""))
print()
chain = case["auditChain"]
print("   audit chain: %d events, %s, head %s..." % (chain["events"], "verified" if chain["valid"] else "BROKEN", chain["headHash"][:16]))
print("   ledger:      %s" % ", ".join("%s %s" % (j["type"], j["postings"][0]["amount"]) for j in case["ledger"]))
PY

  local timeline_requests state valid
  timeline_requests=$(python3 -c '
import json, sys
case = json.load(open(sys.argv[1]))
print(" ".join(sorted({e["requestId"] for e in case["timeline"]})))' "$WORK_DIR/case-file.json")
  state=$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1]))["payment"]["state"])' "$WORK_DIR/case-file.json")
  valid=$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1]))["auditChain"]["valid"])' "$WORK_DIR/case-file.json")

  checks
  check "every step is traceable to its request" "$timeline_requests" \
    "checkout-1 checkout-1-retry checkout-start ops-review-17 psp-delivery-1 psp-delivery-2 psp-delivery-3 settlement-run"
  check "the payment ends settled" "$state" "SETTLED"
  check "the hash chain verifies" "$valid" "True"
}

usage() {
  echo "usage: $0 <idempotency|duplicate-webhook|amount-mismatch|provider-outage|lost-webhook|settlement|reconciliation|four-eyes|security|audit-trail>" >&2
}

require_tools
case "${1:-}" in
  idempotency | duplicate-webhook | amount-mismatch | provider-outage | lost-webhook | settlement | reconciliation | four-eyes | security | audit-trail)
    onboard_merchant "merchant-$RUN_ID"
    "scenario_${1//-/_}"
    ;;
  *) usage; exit 2 ;;
esac

if [ "$CHECK_FAILURES" -gt 0 ]; then
  printf '\n%s check(s) failed\n' "$CHECK_FAILURES"
  exit 1
fi
printf '\nAll checks passed\n'
