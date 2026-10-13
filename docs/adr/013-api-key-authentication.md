# 013 API Key Authentication and Merchant Isolation

## Context

Every endpoint was open, the merchant id was taken from the request body, and review decisions recorded a self-declared `actor`. Anyone could create payments for any merchant, read every payment, and approve refunds under any name.

## Decision

- Every call carries `Authorization: Bearer <key>`. Spring Security runs stateless (no sessions, no CSRF, no form login).
- **Merchant keys** are issued by an operator at onboarding and on rotation, shown once, and stored only as SHA-256 hashes (`pf_m_` + 32 random bytes, so a fast hash is enough; there is nothing to brute-force). Revocation takes effect on the next request.
- **Operator keys** are configured as SHA-256 hashes (`payflow.security.operators`), so the configuration itself holds no usable secret. A startup warning fires if the documented local demo keys are configured.
- The merchant is derived from the key. A `merchantId` in the body must match it (403 otherwise).
- A merchant asking for another merchant's payment or refund gets the same **404** as for a missing one, so ids cannot be probed.
- The actor on every audit event and decision is the authenticated caller (`merchant:<id>`, `operator:<name>`, `system:<job>`, `provider`).
- The provider webhook and the provider simulator authenticate themselves (HMAC signature, provider key) and bypass API keys.

## Alternatives considered

- OAuth2 / JWT with an identity provider: the right choice behind a real gateway, but adds infrastructure a local project cannot run honestly. The `Caller` abstraction is what the rest of the code depends on, so swapping the authentication filter does not touch business logic.
- mTLS for merchants: strong, but heavy for a local demo.

## Consequences

Authorization is enforced in two places: URL rules in `SecurityConfig` (who may call what) and ownership checks in the services (which rows a merchant may touch). Tests cover both, including isolation between two merchants.
