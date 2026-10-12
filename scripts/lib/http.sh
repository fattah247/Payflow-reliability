# Shared helpers for scripts/demo-readable.sh and scripts/scenarios.sh. Source, do not run.
# Callers set MERCHANT_ID before using create_payment.

BASE_URL="${BASE_URL:-http://localhost:8080}"
SIGNATURE="${SIGNATURE:-local-demo-signature}"
CHECK_FAILURES=0

require_tools() {
  local dependency
  for dependency in curl python3; do
    command -v "$dependency" >/dev/null 2>&1 || { echo "missing dependency: $dependency" >&2; exit 1; }
  done
  curl -sf "$BASE_URL/actuator/health" >/dev/null || { echo "app is not reachable at $BASE_URL" >&2; exit 1; }
}

# request METHOD PATH BODY [HEADER...]  -> sets STATUS, BODY and REPLAYED (the Idempotent-Replayed header)
request() {
  local method="$1" path="$2" body="$3"
  shift 3
  local args=(-sS -X "$method" "$BASE_URL$path" -H 'Content-Type: application/json'
    -w $'\n%header{idempotent-replayed}\n%{http_code}')
  local header
  for header in "$@"; do args+=(-H "$header"); done
  if [ -n "$body" ]; then args+=(-d "$body"); fi
  local output rest
  output=$(curl "${args[@]}")
  STATUS="${output##*$'\n'}"
  rest="${output%$'\n'*}"
  REPLAYED="${rest##*$'\n'}"
  BODY="${rest%$'\n'*}"
}

# pick DOTTED.PATH  -> prints a field of BODY (list indexes allowed, e.g. rows.0.outcome)
pick() {
  printf '%s' "$BODY" | python3 -c '
import decimal, json, sys
value = json.load(sys.stdin, parse_float=decimal.Decimal)
for key in sys.argv[1].split("."):
    value = value[int(key)] if isinstance(value, list) else value[key]
print(json.dumps(value) if isinstance(value, (list, dict, bool)) or value is None else value)' "$1"
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

show() {
  printf '   %-36s %s\n' "$1" "$2"
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

create_payment() {
  local reference="$1" amount="$2"
  shift 2
  request POST /api/payment-intents \
    "{\"merchantId\": \"$MERCHANT_ID\", \"merchantReference\": \"$reference\", \"amount\": $amount, \"currency\": \"IDR\"}" \
    "Idempotency-Key: key-$reference" "$@"
}

webhook() {
  local payment="$1" event="$2" tx="$3" status="$4" amount="$5"
  shift 5
  request POST /fake-provider/webhook \
    "{\"providerEventId\": \"$event\", \"providerTransactionId\": \"$tx\", \"paymentIntentId\": \"$payment\", \"status\": \"$status\", \"amount\": $amount, \"currency\": \"IDR\"}" \
    "X-Provider-Signature: $SIGNATURE" "$@"
}

resolve_case() {
  local case_id="$1" action="$2" note="$3"
  shift 3
  request POST "/api/review-cases/$case_id/resolve" \
    "{\"actor\": \"ops-demo\", \"note\": \"$note\", \"action\": \"$action\"}" "$@"
}
