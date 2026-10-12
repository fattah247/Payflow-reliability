#!/usr/bin/env bash
# Quick end-to-end flow. Every run uses fresh ids, so it can be repeated without resetting the database.
set -euo pipefail

BASE_URL="${BASE_URL:-http://localhost:8080}"
SIGNATURE="${SIGNATURE:-local-demo-signature}"
RUN_ID="$(date +%Y%m%d%H%M%S)"
MERCHANT_ID="merchant-demo-$RUN_ID"
KEY="demo-key-$RUN_ID"
PROVIDER_TX_ID="provider-tx-demo-$RUN_ID"

need() {
  command -v "$1" >/dev/null 2>&1 || {
    echo "missing dependency: $1" >&2
    exit 1
  }
}

json_field() {
  python3 -c 'import json,sys; print(json.load(sys.stdin)[sys.argv[1]])' "$1"
}

need curl
need python3

create_body="{\"merchantId\": \"$MERCHANT_ID\", \"merchantReference\": \"ORDER-$RUN_ID\", \"amount\": 125000, \"currency\": \"IDR\"}"

echo "creating payment intent"
payment_intent_id=$(curl -sS -X POST "$BASE_URL/api/payment-intents" \
  -H 'Content-Type: application/json' -H "Idempotency-Key: $KEY" -d "$create_body" | json_field id)
echo "payment_intent_id=$payment_intent_id"

echo "replaying same payment intent request"
replay_id=$(curl -sS -X POST "$BASE_URL/api/payment-intents" \
  -H 'Content-Type: application/json' -H "Idempotency-Key: $KEY" -d "$create_body" | json_field id)
echo "replay_id=$replay_id"

echo "starting payment"
curl -sS -X POST "$BASE_URL/api/payment-intents/$payment_intent_id/start" >/dev/null

webhook_body="{
  \"providerEventId\": \"evt-demo-$RUN_ID\",
  \"providerTransactionId\": \"$PROVIDER_TX_ID\",
  \"paymentIntentId\": \"$payment_intent_id\",
  \"status\": \"SUCCESS\",
  \"amount\": 125000,
  \"currency\": \"IDR\"
}"

echo "sending success webhook"
curl -sS -X POST "$BASE_URL/fake-provider/webhook" \
  -H 'Content-Type: application/json' -H "X-Provider-Signature: $SIGNATURE" -d "$webhook_body" | json_field outcome

echo "redelivering the same webhook (provider retry)"
curl -sS -X POST "$BASE_URL/fake-provider/webhook" \
  -H 'Content-Type: application/json' -H "X-Provider-Signature: $SIGNATURE" -d "$webhook_body" | json_field outcome

echo "creating settlement batch"
batch_id=$(curl -sS -X POST "$BASE_URL/api/settlements/batches?merchantId=$MERCHANT_ID" | json_field id)
echo "batch_id=$batch_id"

echo "sending reconciliation mismatch"
curl -sS -X POST "$BASE_URL/api/reconciliation/reports" \
  -H 'Content-Type: application/json' \
  -d "{
    \"rows\": [
      {
        \"providerTransactionId\": \"$PROVIDER_TX_ID\",
        \"amount\": 124000,
        \"currency\": \"IDR\",
        \"providerStatus\": \"SUCCESS\",
        \"settlementDate\": \"2026-01-01\"
      }
    ]
  }"
echo

echo "open review cases for this payment"
curl -sS "$BASE_URL/api/review-cases?paymentIntentId=$payment_intent_id"
echo

echo "audit trail"
curl -sS "$BASE_URL/api/audit-events?paymentIntentId=$payment_intent_id"
echo
