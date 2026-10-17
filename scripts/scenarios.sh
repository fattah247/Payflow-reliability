#!/usr/bin/env bash
# Runs one reliability scenario against a running app and checks its outcome.
# The screenshots in docs/screenshots are the output of these scenarios.
#
#   ./scripts/scenarios.sh <scenario>
#
# Scenarios: idempotency, duplicate-webhook, amount-mismatch, provider-outage, lost-webhook, settlement,
#            reconciliation, four-eyes, security, audit-trail,
#            outbound-key-crash, bank-reconciliation, fee-deduction, missing-credit, returned-payout,
#            statement-reimport, slow-but-up, device-risk, chaos, merchant-webhooks
#
# Simulated customers (slow-but-up, device-risk) and the chaos run are seeded: the same seed plays the same
# customers and the same faults. Their parameters are model assumptions, never real data.
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
  header "Provider report against the ledger" \
    "Every reported row gets a verdict; even a clean match waits for the bank before it counts as reconciled."

  local matched mismatched unreported
  matched=$(paid "ORDER-A-$RUN_ID" 1000)
  mismatched=$(paid "ORDER-B-$RUN_ID" 2000)
  unreported=$(paid "ORDER-C-$RUN_ID" 3000)
  quiet_outbox
  local batch
  batch=$(settle_now)
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
  local after_report
  after_report=$(states "$matched" "$mismatched" "$unreported")
  show "payment states (A B C)" "$after_report (the provider's word is not the bank's)"

  batch_view "$batch"
  bank_catch_up "$(pick provider.expectedValueDate)"
  batch_view "$batch"
  show "the bank credits the payout" "$(pick payoutStatus)"

  as_ops request POST /api/reconciliation/reports "$report"
  printf '   same report imported again, after the bank\n'
  local second_outcomes
  second_outcomes=$(outcomes 2>/dev/null)
  printf '     %s\n' "$second_outcomes"
  as_ops request GET "/api/review-cases" ""
  local opened_by_second
  opened_by_second=$(( $(size) - cases_before - opened_by_first ))
  show "review cases opened" "$opened_by_first by first import, $opened_by_second by second"

  local after_bank
  after_bank=$(states "$matched" "$mismatched" "$unreported")
  show "payment states (A B C)" "$after_bank"

  checks
  check "each row gets its own verdict" "$first_outcomes" \
    "MATCHED_AWAITING_BANK MISMATCH NOT_FOUND_LOCALLY DUPLICATE_ROW MISSING_FROM_REPORT"
  check "a payout the provider did not report is flagged" "$(printf '%s' "$first_outcomes" | grep -c MISSING_FROM_REPORT)" "1"
  check "a clean match waits for the bank" "$after_report" "SETTLED SETTLED SETTLED"
  check "only the clean match is reconciled, once the bank confirms" "$after_bank" "RECONCILED SETTLED SETTLED"
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

scenario_outbound_key_crash() {
  header "The provider acts, then the call times out" \
    "One business operation, one outbound key: the worker cannot know its call worked, and money still moves once."

  local payment
  payment=$(paid "ORDER-$RUN_ID" 200000)
  quiet_outbox
  provider_fault REFUND TIMEOUT_AFTER_COMMIT 1
  as_merchant request POST "/api/refunds/payment-intents/$payment" \
    '{"amount": 50000, "reason": "damaged item"}' "Idempotency-Key: client-refund-$RUN_ID"
  local refund
  refund=$(pick id)
  show "refund of 50000 requested" "client key client-refund-$RUN_ID"
  show "the provider" "refunds the customer, then answers too late"
  wait_for_refund "$refund" COMPLETED 45 || true
  local refund_status
  refund_status=$(pick status)

  as_ops request GET "/api/payment-intents/$payment/case-file" ""
  local key attempts journals
  key=$(json "[c['idempotencyKey'] for c in b['providerCalls'] if c['commandType'] == 'REFUND'][0]")
  attempts=$(json "[c['attempts'] for c in b['providerCalls'] if c['commandType'] == 'REFUND'][0]")
  journals=$(json "sum(1 for j in b['ledger'] if j['type'] == 'REFUND')")
  show "outbound key, derived once" "$key"
  show "worker attempts" "$attempts (after the timeout it looked the key up)"
  as_provider request GET "/provider-sim/v1/requests?idempotencyKey=$key" ""
  local refund_log refund_applied
  refund_log=$(json "', '.join(r['outcome'] for r in b)")
  refund_applied=$(json "sum(1 for r in b if r['outcome'] == 'APPLIED')")
  show "provider's log for that key" "$refund_log"
  as_provider request GET "/provider-sim/v1/payments/$payment" ""
  local refunded
  refunded=$(money "$(pick refundedAmount)")
  show "refunded at the provider" "$refunded IDR; refund journals in the ledger: $journals"

  quiet_outbox
  provider_fault PAYOUT TIMEOUT_AFTER_COMMIT 1
  local batch
  batch=$(settle_now)
  batch_view "$batch"
  local payout_status net
  payout_status=$(pick payoutStatus)
  net=$(money "$(pick provider.net)")
  as_provider request GET "/provider-sim/v1/requests?idempotencyKey=payout-$batch" ""
  local payout_log payout_applied
  payout_log=$(json "', '.join(r['outcome'] for r in b)")
  payout_applied=$(json "sum(1 for r in b if r['outcome'] == 'APPLIED')")
  show "payout of the remaining 150000" "same fault: the provider pays, then times out"
  show "outbound key" "payout-$batch"
  show "provider's log for that key" "$payout_log"
  show "payout" "$payout_status, net $net IDR sent once"

  checks
  check "the refund completes" "$refund_status" "COMPLETED"
  check "the key comes from the refund, not the attempt" "$key" "refund-$refund"
  check "the provider applied the refund once" "$refund_applied $refunded $journals" "1 50000 1"
  check "the provider paid out once, under payout-<batch>" "$payout_applied" "1"
  check "the payout is in transit, sent once" "$payout_status" "IN_TRANSIT"
}

scenario_bank_reconciliation() {
  header "Three-way reconciliation: ledger, provider, bank" \
    "A payout lands only on a bank credit matching reference, amount and value date; only then is a payment reconciled."

  local a b c
  a=$(paid "ORDER-A-$RUN_ID" 100000)
  b=$(paid "ORDER-B-$RUN_ID" 250000)
  c=$(paid "ORDER-C-$RUN_ID" 400000)
  show "captured through the provider" "100000 + 250000 + 400000 IDR"
  quiet_outbox
  local batch
  batch=$(settle_now)
  batch_view "$batch"
  local reference value_date
  reference=$(pick payoutReference)
  value_date=$(pick provider.expectedValueDate)
  show "payout sent (ledger)" "$(money "$(pick ledger.batchTotal)") IDR, reference $reference"
  show "provider accepted" "fee $(money "$(pick provider.fee)"), net $(money "$(pick provider.net)"), value date $value_date"

  provider_report
  local after_report
  after_report=$(states "$a" "$b" "$c")
  show "provider report imported" "payments $after_report"

  bank_catch_up "$(plus_days "$value_date" -1)"
  batch_view "$batch"
  local before_credit
  before_credit=$(pick payoutStatus)
  show "statements to the day before" "no credit: payout $before_credit"

  bank_catch_up "$value_date"
  batch_view "$batch"
  local landed all_checks
  landed=$(pick payoutStatus)
  all_checks=$(json "all(c['passed'] for c in b['checks'] if c['passed'] is not None)")
  show "bank statement for $value_date" "credit found: payout $landed"
  printf '%s' "$BODY" | python3 -c '
import json, sys
batch = json.load(sys.stdin)
import re
for line in batch["bank"]:
    print("     bank line: received %s, charges %s, value %s, %s" % (int(line["received"]), int(line["charges"]),
                                                                  line["valueDate"], line["status"]))
for check in batch["checks"]:
    mark = {True: "yes", False: "NO", None: "--"}[check["passed"]]
    detail = re.sub(r"\.00\b", "", check["detail"])
    if check["name"] in ("provider accepted the payout", "bank credit carries the payout reference"):
        detail = ""
    print("     %-3s  %-44s %s" % (mark, check["name"].replace("ledger: gross = received + provider fee + bank charges",
                                                              "ledger: gross = received + fees"), detail))'
  three_way
  local after_bank open
  after_bank=$(states "$a" "$b" "$c")
  as_ops request GET "/api/reconciliation/breaks?status=OPEN&batchId=$batch" ""
  open=$(size)
  show "payments after the bank" "$after_bank"
  show "open breaks for this payout" "$open"

  checks
  check "the provider's report alone reconciles nothing" "$after_report" "SETTLED SETTLED SETTLED"
  check "without a bank credit the payout stays in transit" "$before_credit" "IN_TRANSIT"
  check "the matched credit lands the payout" "$landed" "LANDED"
  check "reference, amount, value date and fees all agree" "$all_checks" "True"
  check "only now are the payments reconciled" "$after_bank" "RECONCILED RECONCILED RECONCILED"
  check "nothing left to explain" "$open" "0"
}

scenario_fee_deduction() {
  header "Fees on both sides of the transfer" \
    "Provider fee and bank charge are each their own ledger line, and gross - fees = cash received, to the cent."

  paid "ORDER-$RUN_ID" 500000 > /dev/null
  quiet_outbox
  bank_fault NET_OF_FEES '"amount": 6500'
  local batch
  batch=$(settle_now)
  batch_view "$batch"
  local value_date gross fee net
  value_date=$(pick provider.expectedValueDate)
  gross=$(money "$(pick ledger.batchTotal)")
  fee=$(money "$(pick provider.fee)")
  net=$(money "$(pick provider.net)")
  show "payout (gross)" "$gross IDR"
  show "provider fee (30 bps), net sent" "$fee, $net"
  show "the bank" "credits the transfer minus a 6500 charge"
  bank_catch_up "$value_date"
  batch_view "$batch"
  local status received charges equation
  status=$(pick payoutStatus)
  received=$(money "$(pick bank.0.received)")
  charges=$(money "$(pick bank.0.charges)")
  equation=$(json "[c['detail'] for c in b['checks'] if c['name'].startswith('ledger: gross')][0]")
  show "bank line" "received $received, charges $charges: payout $status"

  as_ops request GET "/api/ledger/journals?reference=$batch" ""
  printf '%s' "$BODY" | python3 -c '
import json, sys
for journal in json.load(sys.stdin):
    if journal["type"] in ("PAYOUT_IN_TRANSIT", "PAYOUT_LANDED"):
        print("     %s" % journal["type"])
        for posting in journal["postings"]:
            amount = posting["amount"]
            side = "debit " if amount > 0 else "credit"
            print("       %s %-22s %12s" % (side, posting["account"], "{:,.2f}".format(abs(amount))))'
  local landed_lines
  landed_lines=$(json "' '.join(p['account'] for j in b if j['type'] == 'PAYOUT_LANDED' for p in j['postings'])")
  show "ledger: gross = received + fees" "$equation"

  checks
  check "the provider's fee is 30 bps of the gross" "$fee $net" "1500 498500"
  check "a credit net of a bank charge still lands" "$status $received $charges" "LANDED 492000 6500"
  check "cash, provider fee and bank charge are separate lines" "$landed_lines" "bank_cash provider_fees bank_charges payouts_in_transit"
  check "gross = received + provider fee + bank charge" "$equation" "500000.00 = 492000.00 + 1500.00 + 6500.00"
}

scenario_missing_credit() {
  header "The provider says it paid; the bank never credits" \
    "Missing only once every statement through the value-date window is in; then a break with an owner and a deadline."

  local payment
  payment=$(paid "ORDER-$RUN_ID" 300000)
  quiet_outbox
  bank_fault DROP_CREDIT
  local batch
  batch=$(settle_now)
  batch_view "$batch"
  local value_date net
  value_date=$(pick provider.expectedValueDate)
  net=$(money "$(pick provider.net)")
  show "provider" "paid out $net IDR, value date $value_date"
  show "the bank" "never books the credit"

  bank_catch_up "$value_date"
  batch_view "$batch"
  local inside_window
  inside_window=$(pick payoutStatus)
  show "statements through $value_date" "no credit yet: $inside_window (late credits are allowed)"

  bank_catch_up "$(plus_days "$value_date" 2)"
  batch_view "$batch"
  local status reason
  status=$(pick payoutStatus)
  reason=$(pick brokenReason)
  show "statements through $(plus_days "$value_date" 2)" "window closed: $status ($reason)"
  as_ops request GET "/api/reconciliation/breaks?status=OPEN&batchId=$batch" ""
  local category owner amount due
  category=$(pick 0.category)
  owner=$(pick 0.owner)
  amount=$(money "$(pick 0.amount)")
  due=$(pick 0.dueAt)
  show "break" "$category, owner $owner, $amount IDR, due $due"
  as_ops request GET "/api/operations/money-at-risk?merchantId=$MERCHANT_ID" ""
  local at_risk
  at_risk=$(json "[(i['count'], str(int(i['amount'].get('IDR', 0)))) for i in b['items'] if i['kind'] == 'BREAKS_OPEN_UNDER_1_DAY'][0]")
  show "money at risk (open breaks, < 1 day)" "$at_risk"
  local state
  state=$(states "$payment")
  show "payment" "$state"

  checks
  check "inside the window the payout is only late" "$inside_window" "IN_TRANSIT"
  check "after the window it is broken, credit missing" "$status $reason" "BROKEN MISSING_CREDIT"
  check "the break says what, who and how much" "$category $owner $amount" "MISSING_CREDIT TREASURY $net"
  check "the money at risk shows it" "$at_risk" "(1, '$net')"
  check "the payment is not reconciled" "$state" "SETTLED"
  check "every money rule still holds" "$(rule_holds UNMATCHED_MONEY_HAS_A_BREAK) $(rule_holds BOOKS_BALANCE)" "True True"
}

scenario_returned_payout() {
  header "The bank credits the payout, then sends it back" \
    "A returned transfer breaks the payout and puts the money back where the ledger can see it."

  local payment
  payment=$(paid "ORDER-$RUN_ID" 200000)
  quiet_outbox
  bank_fault RETURN_PAYOUT '"days": 1'
  local batch
  batch=$(settle_now)
  batch_view "$batch"
  local value_date net reference
  value_date=$(pick provider.expectedValueDate)
  net=$(money "$(pick provider.net)")
  reference=$(pick payoutReference)
  show "provider" "paid out $net IDR, value date $value_date"

  bank_catch_up "$value_date"
  batch_view "$batch"
  local first
  first=$(pick payoutStatus)
  show "statement for $value_date" "credit arrives: $first"

  bank_catch_up "$(plus_days "$value_date" 1)"
  batch_view "$batch"
  local status reason returned lines
  status=$(pick payoutStatus)
  reason=$(pick brokenReason)
  returned=$(money "$(pick bank.1.received)")
  lines=$(json "', '.join('%s %s' % (l['status'], int(l['received'])) for l in b['bank'])")
  show "statement for $(plus_days "$value_date" 1)" "the bank returns it: $status ($reason)"
  show "bank lines for the payout" "$lines"
  as_ops request GET "/api/reconciliation/breaks?status=OPEN&batchId=$batch" ""
  local category owner
  category=$(pick 0.category)
  owner=$(pick 0.owner)
  show "break" "$category, owner $owner"
  as_ops request GET "/api/bank/lines?endToEndId=$reference&status=RETURN" ""
  local return_line
  return_line=$(pick 0.id)
  as_ops request GET "/api/ledger/journals?reference=$return_line" ""
  local journal
  journal=$(json "'; '.join('%s: ' % j['type'] + ', '.join('%s %s %s' % ('debit' if p['amount'] > 0 else 'credit', p['account'], int(abs(p['amount']))) for p in j['postings']) for j in b)")
  show "ledger" "$journal"
  local state
  state=$(states "$payment")
  show "payment" "$state"

  checks
  check "the credit first lands the payout" "$first" "LANDED"
  check "the return breaks it" "$status $reason" "BROKEN RETURNED"
  check "both bank lines are explained" "$lines" "MATCHED $net, RETURN -$net"
  check "the money is the provider's to send again" "$journal" \
    "PAYOUT_RETURNED: debit provider_receivable $net, credit bank_cash $net"
  check "someone owns the follow-up" "$category" "RETURNED_PAYOUT"
  check "the payment is not reconciled" "$state" "SETTLED"
  check "the books still balance" "$(rule_holds BOOKS_BALANCE)" "True"
}

scenario_statement_reimport() {
  header "The same statement, again and again" \
    "Statements imported twice, re-sent by the bank, or re-issued under a new number never book a credit twice."

  paid "ORDER-$RUN_ID" 150000 > /dev/null
  quiet_outbox
  local batch
  batch=$(settle_now)
  batch_view "$batch"
  local value_date reference
  value_date=$(pick provider.expectedValueDate)
  reference=$(pick payoutReference)
  bank_catch_up "$value_date"
  batch_view "$batch"
  local statement status
  statement=$(pick bank.0.statementId)
  status=$(pick payoutStatus)
  show "statement $statement" "imported: payout $status"

  as_bank request GET "/bank-sim/v1/statements/$statement" ""
  printf '%s' "$BODY" > "$WORK_DIR/statement.xml"
  local again=""
  local i
  for i in 1 2; do
    curl -sS -X POST "$BASE_URL/api/bank/statements" -H "Authorization: Bearer $OPS_KEY" -H 'Content-Type: application/xml' \
      --data-binary @"$WORK_DIR/statement.xml" > "$WORK_DIR/again-$i.json"
    again="$again $(python3 -c 'import json,sys; r=json.load(open(sys.argv[1])); print("%s/%s" % (r["outcome"], r["newLines"]))' "$WORK_DIR/again-$i.json")"
  done
  show "imported by hand, twice more" "${again# } (outcome/new lines)"

  bank_fault RESEND_STATEMENT
  close_bank_through "$(plus_days "$value_date" 1)"
  pull_statements
  local resent
  resent=$(json "' '.join(sorted(set(r['outcome'] for r in b)))")
  show "the bank re-sends a statement" "pull: ${resent:-nothing new}"

  # Same entries, new statement id and number (ids stay within camt.053's 35 characters).
  python3 - "$WORK_DIR/statement.xml" > "$WORK_DIR/reissued.xml" <<'PY'
import re, sys, time
xml = open(sys.argv[1]).read()
statement_id = re.search(r"<Stmt>\s*<Id>([^<]+)</Id>", xml).group(1)
xml = xml.replace(statement_id, "%s-R%d" % (statement_id, int(time.time()) % 100000))
print(re.sub(r"<ElctrncSeqNb>\d+</ElctrncSeqNb>", "<ElctrncSeqNb>%d</ElctrncSeqNb>" % (900000 + int(time.time()) % 90000), xml, count=1))
PY
  curl -sS -X POST "$BASE_URL/api/bank/statements" -H "Authorization: Bearer $OPS_KEY" -H 'Content-Type: application/xml' \
    --data-binary @"$WORK_DIR/reissued.xml" > "$WORK_DIR/reissued.json"
  local reissued copy_id
  reissued=$(python3 -c 'import json,sys; r=json.load(open(sys.argv[1])); print("%s/%s" % (r["outcome"], r["newLines"]))' "$WORK_DIR/reissued.json")
  copy_id=$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1]))["statementId"])' "$WORK_DIR/reissued.json")
  show "the bank re-issues it, new number" "$copy_id: $reissued"
  as_ops request GET "/api/reconciliation/breaks?status=OPEN&category=STATEMENT_CONFLICT" ""
  local conflict owner
  conflict=$(json "[x['detail'] for x in b if x['statementId'] == '$copy_id'][0]")
  owner=$(json "[x['owner'] for x in b if x['statementId'] == '$copy_id'][0]")
  show "break (owner $owner)" "$conflict"

  as_ops request GET "/api/bank/lines?endToEndId=$reference" ""
  local booked
  booked=$(size)
  as_ops request GET "/api/ledger/journals?reference=$batch" ""
  local landings
  landings=$(json "sum(1 for j in b if j['type'] == 'PAYOUT_LANDED')")
  show "bank lines for this payout" "$booked"
  show "landing journals for the payout" "$landings"

  checks
  check "the first import lands the payout" "$status" "LANDED"
  check "re-imports book nothing" "${again# }" "ALREADY_IMPORTED/0 ALREADY_IMPORTED/0"
  check "a re-issued copy books nothing and opens a break" "$reissued $(printf '%s' "$conflict" | grep -c 'repeats')" "IMPORTED/0 1"
  check "one bank line, one landing" "$booked $landings" "1 1"
  check "every bank entry is booked once" "$(rule_holds BANK_ENTRY_BOOKED_ONCE)" "True"
}

scenario_slow_but_up() {
  header "The provider is slow, and every health check passes" \
    "Seeded customers through the real API: what slowness costs in money, with zero duplicate charges."
  trap 'as_provider request DELETE /provider-sim/v1/latency "" > /dev/null 2>&1; rm -rf "$WORK_DIR"' EXIT

  checkout_run() {
    as_ops request POST /api/simulations/checkout-runs \
      "{\"seed\": 42, \"phase\": \"$1\", \"merchantApiKey\": \"$MERCHANT_KEY\", \"providerSlow\": $2, \"holdWebhooks\": $3, \"customers\": 40}"
  }
  checkout_run normal false null
  local normal_paid normal_left normal_retries
  normal_paid=$(pick paid.count)
  normal_left=$(pick abandoned.count)
  normal_retries=$(pick retries)
  deliver_webhooks
  show "normal provider, 40 customers" "$normal_paid paid, $normal_left left, $normal_retries retries"

  checkout_run slow true true
  local paid_count paid_amount left left_amount retries absorbed duplicates
  paid_count=$(pick paid.count)
  paid_amount=$(money "$(pick paid.amount)")
  left=$(pick abandoned.count)
  left_amount=$(money "$(pick abandoned.amount)")
  retries=$(pick retries)
  absorbed=$(pick retriesAbsorbed)
  duplicates=$(pick duplicateCharges.count)
  show "slow provider, 40 more customers" "$paid_count paid ($paid_amount IDR), $left left ($left_amount IDR)"
  show "" "$retries retries, $absorbed answered by the idempotency key, $duplicates duplicate charges"
  printf '   (simulated customers: seed 42, parameters in payflow.loadgen.checkout.*; not real behaviour)\n'

  sleep 11
  as_ops request GET "/api/operations/money-at-risk?merchantId=$MERCHANT_ID" ""
  local pending abandoned per_minute dup_measured
  pending=$(json "[(i['count'], str(int(i['amount'].get('IDR', 0)))) for i in b['items'] if i['kind'] == 'PENDING_BEYOND_SLA'][0]")
  abandoned=$(json "[(i['count'], str(int(i['amount'].get('IDR', 0)))) for i in b['items'] if i['kind'] == 'ABANDONED'][0]")
  per_minute=$(json "[str(int(i['perMinute'].get('IDR', 0))) for i in b['items'] if i['kind'] == 'ABANDONED'][0]")
  dup_measured=$(json "[i['count'] for i in b['items'] if i['kind'] == 'DUPLICATE_CHARGES'][0]")
  anonymous request GET /actuator/health ""
  local health
  health=$(pick status)
  printf '\n   measured by the payment system, 11 s later\n'
  show "past the 10 s confirmation SLO" "$pending (count, IDR)"
  show "abandoned in the last 15 minutes" "$abandoned (count, IDR), $per_minute IDR per minute"
  show "duplicate charges" "$dup_measured"
  show "health" "$health"

  checkout_run recovered false null
  deliver_webhooks
  local i
  for i in $(seq 1 20); do
    as_ops request GET "/api/operations/money-at-risk?merchantId=$MERCHANT_ID" ""
    [ "$(json "[i['count'] for i in b['items'] if i['kind'] == 'PENDING_BEYOND_SLA'][0]")" = "0" ] && break
    deliver_webhooks
    sleep 0.5
  done
  local after
  after=$(json "[i['count'] for i in b['items'] if i['kind'] == 'PENDING_BEYOND_SLA'][0]")
  show "provider recovers" "held confirmations arrive: $after past the SLO"

  checks
  check "a fast provider loses nobody" "$normal_paid" "40"
  check "slowness costs real checkouts" "$([ "$left" -gt 0 ] && echo yes)" "yes"
  check "the system measures what the customers did" "$abandoned" "($left, '$left_amount')"
  check "paid but unconfirmed is money at risk" "$pending" "($paid_count, '$paid_amount')"
  check "every retry reused its key; nobody charged twice" "$absorbed $duplicates $dup_measured" "$retries 0 0"
  check "and the health check says UP throughout" "$health" "UP"
  check "recovery clears the backlog" "$after" "0"
}

scenario_device_risk() {
  header "Device risk as money" \
    "Step-ups wait for the customer, blocks never reach the provider, and every threshold has a price in money."

  local rooted='{"deviceKeyId": "device-7", "level": "LOW", "simulated": true, "stepUpKeyAvailable": true,
    "signals": [{"id": "root_package", "category": "ROOT", "severity": "HIGH", "source": "DEVICE_CHECK"}]}'
  local emulator='{"deviceKeyId": "device-8", "simulated": true, "stepUpKeyAvailable": true,
    "signals": [{"id": "emulator_fingerprint", "category": "EMULATOR", "severity": "MEDIUM", "source": "DEVICE_CHECK"}]}'
  as_merchant request POST /api/payment-intents \
    "{\"merchantReference\": \"ROOTED-$RUN_ID\", \"amount\": 120000, \"currency\": \"IDR\", \"action\": \"DIGITAL_GOODS\", \"riskEvidence\": $rooted}" \
    "Idempotency-Key: key-ROOTED-$RUN_ID"
  local blocked blocked_state
  blocked=$(pick id)
  blocked_state=$(pick state)
  start_payment "$blocked"
  local blocked_start="$STATUS $(pick code)"
  as_ops request GET "/api/risk/decisions/$blocked" ""
  show "rooted phone (claims LOW)" "$blocked_state by $(pick rule), $(pick totalPoints) points, recomputed level $(pick deviceLevel)"
  show "shop tries to start it" "HTTP $blocked_start"

  as_merchant request POST /api/payment-intents \
    "{\"merchantReference\": \"EMU-$RUN_ID\", \"amount\": 80000, \"currency\": \"IDR\", \"riskEvidence\": $emulator}" \
    "Idempotency-Key: key-EMU-$RUN_ID"
  local held held_state method
  held=$(pick id)
  held_state=$(pick state)
  method=$(pick requiredConfirmation)
  start_payment "$held"
  local held_start="$STATUS $(pick code)"
  as_merchant request POST "/api/payment-intents/$held/confirm" "{\"method\": \"$method\"}"
  start_payment "$held"
  local confirmed_start="$STATUS $(pick state)"
  show "emulator (one MEDIUM signal)" "$held_state, confirm with $method"
  show "shop tries to start it" "HTTP $held_start"
  show "customer confirms with $method" "start: HTTP $confirmed_start"

  onboard_merchant "merchant-traffic-$RUN_ID"
  as_ops request POST /api/simulations/risk-runs \
    "{\"seed\": 42, \"phase\": \"risk\", \"merchantApiKey\": \"$MERCHANT_KEY\", \"customers\": 200}"
  printf '%s' "$BODY" > "$WORK_DIR/truth.json"
  deliver_webhooks
  as_ops request GET "/api/risk/report?merchantId=$MERCHANT_ID" ""
  printf '%s' "$BODY" > "$WORK_DIR/report.json"
  as_ops request GET "/api/risk/threshold-sweep?parameter=STEP_UP&from=1&to=6&merchantId=$MERCHANT_ID" ""
  printf '%s' "$BODY" > "$WORK_DIR/sweep.json"
  printf '\n   200 labelled customers (seed 42; fraud share, devices and confirm rates are model assumptions)\n'
  python3 - "$WORK_DIR/truth.json" "$WORK_DIR/report.json" "$WORK_DIR/sweep.json" <<'PY'
import json, sys
truth, report, sweep = (json.load(open(path)) for path in sys.argv[1:4])
def t(tally):
    return "%3d  %13s" % (tally["count"], "{:,}".format(int(tally["amount"])))
print("     %-26s %-18s %s" % ("", "honest", "fraud"))
for what in ("allowed", "stepped up, confirmed", "stepped up, not confirmed", "blocked"):
    zero = {"count": 0, "amount": 0}
    print("     %-26s %-18s %s" % (what, t(truth["legitimateBy"].get(what, zero)), t(truth["fraudBy"].get(what, zero))))
print()
print("     measured from decisions + labels")
print("       fraud stopped            %s" % t(report["fraudStopped"]))
print("       fraud loss               %s" % t(report["fraudLoss"]))
print("       honest lost to friction  %s" % t(report["legitimateLostToFriction"]))
print("       precision %.3f   recall %.3f" % (report["precision"], report["recall"]))
print()
print("     step-up threshold    flagged    fraud loss      friction   precision  recall")
for row in sweep["rows"]:
    print("       %s %-12d %7d  %12s  %12s   %9s  %6s" % ("->" if row["current"] else "  ", row["value"], row["steppedUp"] + row["blocked"],
          "{:,}".format(int(row["fraudLoss"])), "{:,}".format(int(row["legitimateLostToFriction"])),
          "%.3f" % row["precision"] if row["precision"] is not None else "-", "%.3f" % row["recall"] if row["recall"] is not None else "-"))
print("       (rows other than the current one are expected values: re-decided from stored points)")
PY
  local agree monotone
  agree=$(python3 - "$WORK_DIR/truth.json" "$WORK_DIR/report.json" <<'PY'
import json, sys
truth, report = (json.load(open(path)) for path in sys.argv[1:3])
def tally(by, *keys):
    rows = [by[k] for k in keys if k in by]
    return (sum(r["count"] for r in rows), sum(int(r["amount"]) for r in rows))
same = (tally(truth["fraudBy"], "blocked", "stepped up, not confirmed") == (report["fraudStopped"]["count"], int(report["fraudStopped"]["amount"]))
        and tally(truth["fraudBy"], "allowed", "stepped up, confirmed") == (report["fraudLoss"]["count"], int(report["fraudLoss"]["amount"]))
        and tally(truth["legitimateBy"], "blocked", "stepped up, not confirmed")
            == (report["legitimateLostToFriction"]["count"], int(report["legitimateLostToFriction"]["amount"])))
print(same)
PY
)
  monotone=$(python3 -c '
import json, sys
rows = json.load(open(sys.argv[1]))["rows"]
print(all(b["fraudLoss"] >= a["fraudLoss"] and b["legitimateLostToFriction"] <= a["legitimateLostToFriction"] for a, b in zip(rows, rows[1:]))
      and rows[-1]["fraudLoss"] > rows[0]["fraudLoss"])' "$WORK_DIR/sweep.json")

  checks
  check "a high-severity signal blocks, whatever the device claims" "$blocked_state $blocked_start" "BLOCKED 422 PAYMENT_BLOCKED"
  check "a step-up holds the payment until confirmed" "$held_state $held_start" "REQUIRES_CONFIRMATION 409 STEP_UP_REQUIRED"
  check "once confirmed it starts like any other" "$confirmed_start" "200 PENDING"
  check "measured fraud and friction equal the ground truth" "$agree" "True"
  check "a higher threshold trades friction for fraud loss" "$monotone" "True"
  check "no blocked payment reached the provider" "$(rule_holds BLOCKED_NEVER_REACHES_THE_PROVIDER)" "True"
  check "no step-up started unconfirmed" "$(rule_holds STEP_UP_CONFIRMED_BEFORE_CAPTURE)" "True"
}

scenario_chaos() {
  header "Chaos: a seeded sequence of faults against the running app" \
    "Lost and doubled webhooks, provider timeouts, racing payouts, bank faults, slowness, device risk: the money rules hold."

  quiet_outbox
  as_ops request POST /api/simulations/chaos-runs "{\"seed\": 42, \"steps\": 150, \"merchantApiKey\": \"$MERCHANT_KEY\"}"
  printf '%s' "$BODY" > "$WORK_DIR/chaos.json"
  python3 - "$WORK_DIR/chaos.json" <<'PY'
import json, sys
run = json.load(open(sys.argv[1]))
import textwrap
def line(label, counts):
    text = ", ".join("%s %s" % (k.lower(), v) for k, v in counts.items()) or "none"
    wrapped = textwrap.wrap(text, 84) or [""]
    print("   %-20s %s" % (label, wrapped[0]))
    for rest in wrapped[1:]:
        print("   %-20s %s" % ("", rest))
print("   seed %s, %s steps" % (run["seed"], run["steps"]))
line("actions", run["actions"])
line("faults injected", run["faults"])
line("payments end", run["payments"])
line("payouts end", run["payouts"])
line("breaks", run["breaks"])
print()
print("   money rules after everything settled")
for rule in run["invariants"]["rules"]:
    print("     %-36s %s" % (rule["name"], "holds" if rule["holds"] else "BROKEN: " + "; ".join(rule["violations"][:3])))
PY
  local rules
  rules=$(python3 -c 'import json,sys; print(" ".join(r["name"] for r in json.load(open(sys.argv[1]))["invariants"]["rules"]))' "$WORK_DIR/chaos.json")

  checks
  local rule
  for rule in $rules; do
    check "$rule" "$(python3 -c 'import json,sys; print([r["holds"] for r in json.load(open(sys.argv[1]))["invariants"]["rules"] if r["name"] == sys.argv[2]][0])' "$WORK_DIR/chaos.json" "$rule")" "True"
  done
  check "and across the whole system, right now" "$(as_ops request GET /api/operations/invariants ""; pick allHold)" "true"
}

scenario_merchant_webhooks() {
  header "Telling the shop: signed webhooks, retries, order and catch-up" \
    "Every change reaches the shop once and in order, even when its server fails, answers late or changes its secret."

  local shop="shop-$RUN_ID" sim="/merchant-sim/v1/shops/shop-$RUN_ID"
  as_merchant request PUT /api/webhook-endpoint "{\"url\": \"$BASE_URL$sim/webhooks\"}"
  local registered="$STATUS" secret
  secret=$(pick secret)
  anonymous request PUT "$sim/secret" "{\"secret\": \"$secret\"}"
  show "shop registers its endpoint" "HTTP $registered, secret ${secret:0:10}... shown once"

  local payment
  payment=$(paid "WH-$RUN_ID" 150000)
  wait_for_shop "$sim" "payment" "$payment" 3
  section "a payment, as the shop received it"
  shop_deliveries "$sim"

  section "the shop is down for one delivery while a refund completes"
  anonymous request POST "$sim/faults" '{"failNext": 1, "status": 503}'
  as_merchant request POST "/api/refunds/payment-intents/$payment" '{"amount": 50000, "reason": "Customer request"}' \
    "Idempotency-Key: refund-WH-$RUN_ID"
  local refund
  refund=$(pick id)
  wait_for_refund "$refund" COMPLETED >/dev/null
  wait_for_shop "$sim" "refund" "$refund" 1
  as_merchant request GET "/api/events?paymentIntentId=$payment&type=refund.created" ""
  local created_event
  created_event=$(pick 0.id)
  show "refund.created" "$(pick 0.status), attempt $(pick 0.attempts): $(pick 0.lastError)"
  as_merchant request POST "/api/events/$created_event/redeliver" ""
  wait_for_deliveries "$sim" 6
  shop_deliveries "$sim" refund.

  section "the shop's signing secret is rotated"
  as_merchant request POST /api/webhook-endpoint/rotate-secret ""
  local next_secret until
  next_secret=$(pick secret)
  until=$(pick previousSecretSignsUntil)
  show "new secret" "${next_secret:0:10}...; the previous one also signs until $until"
  create_payment "WH-ROT-$RUN_ID" 1000
  local rotation_payment
  rotation_payment=$(pick id)
  wait_for_deliveries "$sim" 7
  show "shop still on the old secret" "$(last_delivery "$sim")"
  anonymous request PUT "$sim/secret" "{\"secret\": \"$next_secret\"}"
  start_payment "$rotation_payment"
  wait_for_deliveries "$sim" 8
  show "after the shop switches" "$(last_delivery "$sim")"
  local forged_status
  forged_status=$(curl -s -o /dev/null -w '%{http_code}' -X POST "$BASE_URL$sim/webhooks" -H 'Content-Type: application/json' \
    -H "Payflow-Signature: t=$(date +%s),v1=0000000000000000000000000000000000000000000000000000000000000000" \
    --data-binary "{\"id\": \"forged-$RUN_ID\", \"type\": \"payment.updated\", \"sequence\": 99, \"data\": {\"paymentIntentId\": \"$payment\", \"state\": \"REFUNDED\"}}")
  show "a forged delivery (wrong signature)" "HTTP $forged_status"

  section "the events list: the record a shop catches up from"
  as_merchant request GET "/api/events?paymentIntentId=$payment" ""
  json '"\n".join("%-16s seq %-2s %-10s %-10s attempts %s" % (e["type"], e["sequence"], e["state"], e["status"], e["attempts"]) for e in b)' \
    | sed 's/^/   /'
  local all_delivered
  all_delivered=$(json 'all(e["status"] == "DELIVERED" for e in b)')

  anonymous request GET "$sim" ""
  printf '%s' "$BODY" > "$WORK_DIR/shop.json"
  checks
  check "the shop applied the payment's latest state" \
    "$(python3 -c 'import json,sys; p=json.load(open(sys.argv[1]))["payments"][sys.argv[2]]; print(p["state"], p["sequence"])' "$WORK_DIR/shop.json" "$payment")" "SUCCESS 3"
  check "the shop applied the completed refund" \
    "$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1]))["refunds"][sys.argv[2]]["state"])' "$WORK_DIR/shop.json" "$refund")" "COMPLETED"
  check "the late copy of refund.created was ignored as stale" \
    "$(python3 -c 'import json,sys; print([d["outcome"] for d in json.load(open(sys.argv[1]))["deliveries"] if d["type"] == "refund.created"])' "$WORK_DIR/shop.json")" "['FAULT', 'STALE']"
  check "during the rotation overlap deliveries carry both signatures" \
    "$(python3 -c 'import json,sys; d=json.load(open(sys.argv[1]))["deliveries"]; print(d[6]["signatures"], d[6]["outcome"], d[7]["outcome"])' "$WORK_DIR/shop.json")" "2 APPLIED APPLIED"
  check "a forged delivery is refused by the shop" "$forged_status" "401"
  check "every event of the payment delivered" "$all_delivered" "True"
  check "every payment change announced (money rule)" "$(rule_holds PAYMENT_CHANGES_ANNOUNCED)" "True"
}

# wait_for_shop SIM KIND ID SEQUENCE  -> until the simulated shop has applied an event of that payment or refund
wait_for_shop() {
  local sim="$1" kind="$2" id="$3" sequence="$4" i
  for ((i = 0; i < 40; i++)); do
    anonymous request GET "$sim" ""
    if [ "$(json "len([d for d in b['deliveries'] if d['outcome'] == 'APPLIED' and (d['paymentIntentId'] == '$id' if '$kind' == 'payment' else True) and d['type'].startswith('$kind')])")" -ge "$sequence" ] 2>/dev/null; then
      return 0
    fi
    sleep 0.5
  done
}

# wait_for_deliveries SIM COUNT  -> until the simulated shop has received COUNT deliveries
wait_for_deliveries() {
  local sim="$1" count="$2" i
  for ((i = 0; i < 40; i++)); do
    anonymous request GET "$sim" ""
    if [ "$(json 'len(b["deliveries"])')" -ge "$count" ]; then return 0; fi
    sleep 0.5
  done
}

# shop_deliveries SIM [TYPE_PREFIX]  -> what the simulated shop received, in arrival order
shop_deliveries() {
  anonymous request GET "$1" ""
  json '"\n".join("%-16s seq %-2s %-10s signatures %s  %s" % (d["type"], d["sequence"], d["state"], d["signatures"], d["outcome"]) for d in b["deliveries"] if d["type"].startswith("'"${2:-}"'"))' \
    | sed 's/^/   /'
}

# last_delivery SIM  -> the shop's most recent delivery, on one line
last_delivery() {
  anonymous request GET "$1" ""
  json '"%s seq %s, %s signature(s), %s" % (b["deliveries"][-1]["type"], b["deliveries"][-1]["sequence"], b["deliveries"][-1]["signatures"], b["deliveries"][-1]["outcome"])'
}

usage() {
  echo "usage: $0 <idempotency|duplicate-webhook|amount-mismatch|provider-outage|lost-webhook|settlement|reconciliation|four-eyes|security|audit-trail|outbound-key-crash|bank-reconciliation|fee-deduction|missing-credit|returned-payout|statement-reimport|slow-but-up|device-risk|chaos|merchant-webhooks>" >&2
}

require_tools
case "${1:-}" in
  idempotency | duplicate-webhook | amount-mismatch | provider-outage | lost-webhook | settlement | reconciliation | four-eyes | \
    security | audit-trail | outbound-key-crash | bank-reconciliation | fee-deduction | missing-credit | returned-payout | \
    statement-reimport | slow-but-up | device-risk | chaos | merchant-webhooks)
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
