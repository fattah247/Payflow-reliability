# Shared helpers for scripts/demo.sh, scripts/demo-readable.sh and scripts/scenarios.sh. Source, do not run.
#
# Defaults match the local configuration in application.yml. The operator keys and the webhook secret
# are local demo values; never use them outside a developer machine.

BASE_URL="${BASE_URL:-http://localhost:8080}"
OPS_KEY="${OPS_KEY:-pf_o_local_ops-ana}"
OPS_KEY_2="${OPS_KEY_2:-pf_o_local_ops-budi}"
PROVIDER_KEY="${PROVIDER_KEY:-sim_local_provider_key}"
BANK_KEY="${BANK_KEY:-bank_local_statement_key}"
WEBHOOK_SECRET="${WEBHOOK_SECRET:-whsec_local_payflow}"
CHECK_FAILURES=0
API_KEY=""
MERCHANT_ID=""
MERCHANT_KEY=""

require_tools() {
  local dependency
  for dependency in curl python3; do
    command -v "$dependency" >/dev/null 2>&1 || { echo "missing dependency: $dependency" >&2; exit 1; }
  done
  curl -sf "$BASE_URL/actuator/health" >/dev/null || { echo "app is not reachable at $BASE_URL" >&2; exit 1; }
}

# request METHOD PATH BODY [HEADER...]  -> sets STATUS, BODY and REPLAYED (the Idempotent-Replayed header).
# Sends "Authorization: Bearer $API_KEY" when API_KEY is set (see as_merchant, as_ops, ...).
request() {
  local method="$1" path="$2" body="$3"
  shift 3
  local args=(-sS -X "$method" "$BASE_URL$path" -H 'Content-Type: application/json'
    -w $'\n%header{idempotent-replayed}\n%{http_code}')
  if [ -n "${API_KEY:-}" ]; then args+=(-H "Authorization: Bearer $API_KEY"); fi
  local header
  for header in "$@"; do args+=(-H "$header"); done
  if [ -n "$body" ]; then args+=(--data-binary "$body"); fi
  local output rest
  output=$(curl "${args[@]}")
  STATUS="${output##*$'\n'}"
  rest="${output%$'\n'*}"
  REPLAYED="${rest##*$'\n'}"
  BODY="${rest%$'\n'*}"
}

# Run any command as a given caller: as_merchant request GET /api/payment-intents/...
as_merchant() { local API_KEY="$MERCHANT_KEY"; "$@"; }
as_ops() { local API_KEY="$OPS_KEY"; "$@"; }
as_ops2() { local API_KEY="$OPS_KEY_2"; "$@"; }
as_provider() { local API_KEY="$PROVIDER_KEY"; "$@"; }
as_bank() { local API_KEY="$BANK_KEY"; "$@"; }
anonymous() { local API_KEY=""; "$@"; }

# pick DOTTED.PATH  -> prints a field of BODY (list indexes allowed, e.g. rows.0.outcome)
pick() {
  printf '%s' "$BODY" | python3 -c '
import decimal, json, sys
value = json.load(sys.stdin, parse_float=decimal.Decimal)
for key in sys.argv[1].split("."):
    value = value[int(key)] if isinstance(value, list) else value[key]
print(json.dumps(value) if isinstance(value, (list, dict, bool)) or value is None else value)' "$1"
}

# json PYTHON_EXPRESSION  -> evaluates the expression with the parsed BODY as `b` and prints the result
json() {
  printf '%s' "$BODY" | python3 -c '
import decimal, json, sys
b = json.load(sys.stdin, parse_float=decimal.Decimal)
print(eval(sys.argv[1]))' "$1"
}

# money VALUE  -> 348950.0 -> 348950, 6.5 -> 6.50
money() {
  python3 -c 'import decimal, sys; d = decimal.Decimal(sys.argv[1]); print(int(d) if d == d.to_integral_value() else "%.2f" % d)' "$1"
}

# size  -> number of elements in a top-level JSON array BODY
size() {
  printf '%s' "$BODY" | python3 -c 'import json, sys; print(len(json.load(sys.stdin)))'
}

# tally FILE...  -> "1 x HTTP 201, 7 x HTTP 200" from files whose first word is a status code
tally() {
  cut -d' ' -f1 "$@" | sort -r | uniq -c | awk '{printf "%s%s x HTTP %s", (NR > 1 ? ", " : ""), $1, $2}'
}

section() {
  printf '\n== %s ==\n' "$1"
}

# show LABEL VALUE  -> one aligned line; long values continue on following lines
show() {
  local label="$1" line
  while IFS= read -r line; do
    printf '   %-36s %s\n' "$label" "$line"
    label=""
  done < <(printf '%s\n' "$2" | fold -s -w 80)
}

# check LABEL ACTUAL EXPECTED  -> prints PASS or FAIL and remembers failures
check() {
  if [ "$2" = "$3" ]; then
    printf '   PASS  %s\n' "$1"
  else
    printf '   FAIL  %s (expected %s, got %s)\n' "$1" "$3" "$2"
    CHECK_FAILURES=$((CHECK_FAILURES + 1))
  fi
}

# onboard_merchant ID  -> registers the merchant (operator call) and keeps its API key in MERCHANT_KEY
onboard_merchant() {
  MERCHANT_ID="$1"
  as_ops request POST /api/merchants "{\"merchantId\": \"$MERCHANT_ID\", \"displayName\": \"Demo $MERCHANT_ID\"}"
  if [ "$STATUS" != "201" ]; then
    echo "could not register merchant $MERCHANT_ID: HTTP $STATUS $BODY" >&2
    exit 1
  fi
  MERCHANT_KEY=$(pick apiKey.apiKey)
}

# create_payment REFERENCE AMOUNT [HEADER...]  -> as the merchant, idempotency key derived from the reference
create_payment() {
  local reference="$1" amount="$2"
  shift 2
  as_merchant request POST /api/payment-intents \
    "{\"merchantReference\": \"$reference\", \"amount\": $amount, \"currency\": \"IDR\"}" \
    "Idempotency-Key: key-$reference" "$@"
}

start_payment() {
  local payment="$1"
  shift
  as_merchant request POST "/api/payment-intents/$payment/start" "" "$@"
}

# sign BODY [UNIX_SECONDS]  -> X-Provider-Signature value: t=<ts>,v1=<HMAC-SHA256(secret, "<ts>." + body)>
sign() {
  python3 -c '
import hashlib, hmac, sys, time
body, secret = sys.argv[1], sys.argv[2]
ts = sys.argv[3] if len(sys.argv) > 3 and sys.argv[3] else str(int(time.time()))
mac = hmac.new(secret.encode(), (ts + "." + body).encode(), hashlib.sha256).hexdigest()
print("t=%s,v1=%s" % (ts, mac))' "$1" "$WEBHOOK_SECRET" "${2:-}"
}

webhook_body() {
  printf '{"providerEventId": "%s", "providerTransactionId": "%s", "paymentIntentId": "%s", "status": "%s", "amount": %s, "currency": "IDR"}' \
    "$2" "$3" "$1" "$4" "$5"
}

# webhook PAYMENT EVENT TX STATUS AMOUNT [HEADER...]  -> a correctly signed provider webhook
webhook() {
  local payment="$1" event="$2" tx="$3" status="$4" amount="$5"
  shift 5
  local body
  body=$(webhook_body "$payment" "$event" "$tx" "$status" "$amount")
  anonymous request POST /fake-provider/webhook "$body" "X-Provider-Signature: $(sign "$body")" "$@"
}

# resolve_case ID ACTION NOTE [HEADER...]  -> as operator ops-ana
resolve_case() {
  local case_id="$1" action="$2" note="$3"
  shift 3
  as_ops request POST "/api/review-cases/$case_id/resolve" "{\"note\": \"$note\", \"action\": \"$action\"}" "$@"
}

# ---- provider simulator: the customer and the outside world ----------------------------------------

# customer_pays PAYMENT AMOUNT [EXTRA_JSON]  -> the customer pays at the provider, which then sends webhooks
customer_pays() {
  local extra="${3:-}"
  as_provider request POST "/provider-sim/v1/payments/$1/pay" \
    "{\"amount\": $2, \"currency\": \"IDR\"${extra:+, $extra}}"
}

customer_action() {
  as_provider request POST "/provider-sim/v1/payments/$1/$2" "${3:-}"
}

# provider_fault OPERATION MODE TIMES  -> e.g. provider_fault REFUND TIMEOUT_AFTER_COMMIT 1
provider_fault() {
  as_provider request POST /provider-sim/v1/faults "{\"operation\": \"$1\", \"mode\": \"$2\", \"times\": $3}"
}

clear_faults() {
  as_provider request DELETE /provider-sim/v1/faults ""
}

# Background workers deliver webhooks and run provider calls every second; these run them now.
deliver_webhooks() { as_provider request POST /provider-sim/v1/webhooks/dispatch ""; }
run_outbox() { as_ops request POST /api/outbox/run ""; }

# wait_for_state PAYMENT STATE [SECONDS]  -> polls the payment (nudging the workers) until it reaches STATE
wait_for_state() {
  local payment="$1" expected="$2" seconds="${3:-15}" i
  for ((i = 0; i < seconds * 2; i++)); do
    as_ops request GET "/api/payment-intents/$payment" ""
    if [ "$(pick state)" = "$expected" ]; then return 0; fi
    deliver_webhooks
    run_outbox
    sleep 0.5
  done
  as_ops request GET "/api/payment-intents/$payment" ""
  return 1
}

# wait_for_refund REFUND STATUS [SECONDS]
wait_for_refund() {
  local refund="$1" expected="$2" seconds="${3:-15}" i
  for ((i = 0; i < seconds * 2; i++)); do
    as_ops request GET "/api/refunds/$refund" ""
    if [ "$(pick status)" = "$expected" ]; then return 0; fi
    run_outbox
    sleep 0.5
  done
  return 1
}

# paid REFERENCE AMOUNT  -> creates and starts a payment, the customer pays, the webhook lands; prints the id
paid() {
  local reference="$1" amount="$2" id
  create_payment "$reference" "$amount"
  id=$(pick id)
  start_payment "$id"
  customer_pays "$id" "$amount"
  wait_for_state "$id" SUCCESS >/dev/null || { echo "payment $id did not reach SUCCESS" >&2; exit 1; }
  printf '%s' "$id"
}

# provider_tx PAYMENT  -> the provider transaction id recorded for the payment
provider_tx() {
  as_ops request GET "/api/payment-intents/$1/case-file" ""
  pick providerTransactions.0.providerTransactionId
}

# owed MERCHANT  -> what the ledger says the platform owes the merchant in IDR
owed() {
  as_ops request GET "/api/ledger/balances?merchantId=$1&currency=IDR" ""
  printf '%s' "$BODY" | python3 -c '
import decimal, json, sys
rows = json.load(sys.stdin, parse_float=decimal.Decimal)
print(rows[0]["owedToMerchant"] if rows else "0")'
}

# ---- settlement and the bank leg --------------------------------------------------------------------

# quiet_outbox  -> waits until no provider call is pending, so a fault injected next hits this scenario's call
quiet_outbox() {
  local i
  for ((i = 0; i < 60; i++)); do
    run_outbox
    as_ops request GET /api/operations/summary ""
    if [ "$(pick providerCalls.pending)" = "0" ]; then return 0; fi
    sleep 0.5
  done
}

# batch_view BATCH  -> the batch seen three ways: ledger, provider, bank, with every check
batch_view() { as_ops request GET "/api/reconciliation/three-way/batches/$1" ""; }

# settle_now  -> one settlement batch for the merchant, sent to the provider; prints the batch id once in transit
settle_now() {
  as_ops request POST "/api/settlements/batches?merchantId=$MERCHANT_ID" ""
  if [ "$STATUS" != "201" ]; then
    echo "settlement failed: HTTP $STATUS $BODY" >&2
    exit 1
  fi
  local batch i
  batch=$(pick id)
  for ((i = 0; i < 60; i++)); do
    batch_view "$batch"
    if [ "$(pick payoutStatus)" != "SENT" ]; then break; fi
    run_outbox
    sleep 0.5
  done
  printf '%s' "$batch"
}

# bank_fault MODE [EXTRA_JSON]  -> the bank mishandles the next credit (e.g. bank_fault NET_OF_FEES '"amount": 6500')
bank_fault() {
  local extra="${2:-}"
  as_bank request POST /bank-sim/v1/faults "{\"mode\": \"$1\", \"times\": 1${extra:+, $extra}}"
}

# close_bank_through DATE  -> the bank closes its business days through DATE and issues a statement for each
close_bank_through() {
  as_bank request POST /bank-sim/v1/days/close "{\"currency\": \"IDR\", \"through\": \"$1\"}"
}

pull_statements() { as_ops request POST /api/bank/statements/pull ""; }
three_way() { as_ops request POST /api/reconciliation/three-way/run ""; }

# bank_catch_up DATE  -> closes the bank through DATE, imports its statements and runs the three-way reconciliation
bank_catch_up() {
  close_bank_through "$1"
  pull_statements
  three_way
}

# provider_report  -> imports the provider's settlement report for today, as published by the provider
provider_report() {
  as_provider request GET "/provider-sim/v1/reports/settlement?date=$(date +%F)" ""
  as_ops request POST /api/reconciliation/reports "$BODY"
}

# plus_days DATE N  -> DATE + N days
plus_days() {
  python3 -c 'import datetime, sys; print(datetime.date.fromisoformat(sys.argv[1]) + datetime.timedelta(days=int(sys.argv[2])))' "$1" "$2"
}

# states PAYMENT...  -> the payments' states, space separated
states() {
  local payment result=""
  for payment in "$@"; do
    as_ops request GET "/api/payment-intents/$payment" ""
    result="$result $(pick state)"
  done
  printf '%s' "${result# }"
}

# rule_holds NAME  -> True when the money rule holds now (GET /api/operations/invariants)
rule_holds() {
  as_ops request GET /api/operations/invariants ""
  json "[r['holds'] for r in b['rules'] if r['name'] == '$1'][0]"
}
