-- Idempotency keys end to end (ADR 024).

-- 1. A retry under an idempotency key must be the same request. Payments and refunds store the canonical
--    fingerprint of the request that created them; V6 computes it for existing rows and makes it mandatory.
alter table payment_intents add column request_fingerprint varchar(64);
alter table refund_requests add column request_fingerprint varchar(64);

-- 2. Provider simulator: every money-moving request the provider received, with the idempotency key it
--    carried and what the provider did with it (applied, replayed, refused, unavailable). This is the
--    provider's side of the story, used to prove that a resend after a crash carries the same key.
create table sim_api_requests (
    id              uuid                        not null primary key,
    operation       varchar(32)                 not null,
    idempotency_key varchar(150),
    request_hash    varchar(64),
    outcome         varchar(32)                 not null,
    received_at     timestamp(6) with time zone not null
);
create index idx_sim_api_request_key on sim_api_requests (idempotency_key);
