# 014 Signed Webhooks with Replay Protection and Key Rotation

Supersedes the static shared-secret signature of [004](004-webhook-handling.md).

## Context

The webhook compared a static header value. Anyone who saw one request could forge any other, a captured request could be replayed forever, and changing the secret meant downtime.

## Decision

- Header `X-Provider-Signature: t=<unix seconds>,v1=<hex HMAC-SHA256(secret, "<t>." + raw body)>`.
- The signature is verified over the **exact raw bytes before the body is parsed**, so unauthenticated input is never interpreted.
- Timestamps more than 5 minutes from now are rejected (replay window). Inside the window, a replay is harmless: the inbox returns `DUPLICATE_IGNORED`.
- Several secrets may be configured; any of them verifies. Rotation: add the new secret, let the provider switch, remove the old one. The header may carry several `v1` values.
- Comparison is constant time. Rejections are counted by reason (`payflow.webhook.rejected`) and persist nothing, so an attacker cannot fill the review queue.

## Alternatives considered

- IP allow-listing only: brittle with provider infrastructure changes and no integrity.
- Signature without timestamp: replayable forever.

## Consequences

The scheme matches what major providers use, so swapping the simulator for a real provider changes configuration, not code.
