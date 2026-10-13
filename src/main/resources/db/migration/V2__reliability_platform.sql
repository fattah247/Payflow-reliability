-- Reliability platform: currency-aware amounts, merchants and API keys, partial refunds,
-- net settlement, double-entry ledger, transactional outbox, idempotent responses,
-- two-person review approvals, tamper-evident audit chains, and the provider simulator.
-- Data that needs generated ids or hashes is backfilled by the Java migration V3.

-- 1. Amounts: numeric(19, 4) fits every ISO 4217 minor unit (0 to 4 decimals).
alter table payment_intents alter column amount set data type numeric(19, 4);
alter table payment_transactions alter column amount set data type numeric(19, 4);
alter table provider_events alter column amount set data type numeric(19, 4);
alter table settlement_batches alter column total_amount set data type numeric(19, 4);
alter table settlement_batch_items alter column amount set data type numeric(19, 4);
alter table refund_requests alter column amount set data type numeric(19, 4);

-- 2. Merchants and their API keys (stored as SHA-256 hashes; the key itself is shown once).
create table merchants (
    merchant_id  varchar(100)                not null primary key,
    display_name varchar(200)                not null,
    status       varchar(16)                 not null,
    created_by   varchar(150)                not null,
    created_at   timestamp(6) with time zone not null
);
insert into merchants (merchant_id, display_name, status, created_by, created_at)
select distinct merchant_id, merchant_id, 'ACTIVE', 'migration', current_timestamp from payment_intents;
alter table payment_intents add constraint fk_payment_intent_merchant foreign key (merchant_id) references merchants (merchant_id);

create table merchant_api_keys (
    id          uuid                        not null primary key,
    merchant_id varchar(100)                not null,
    key_hash    varchar(64)                 not null,
    key_prefix  varchar(16)                 not null,
    created_by  varchar(150)                not null,
    created_at  timestamp(6) with time zone not null,
    revoked_by  varchar(150),
    revoked_at  timestamp(6) with time zone,
    constraint fk_api_key_merchant foreign key (merchant_id) references merchants (merchant_id),
    constraint uk_api_key_hash unique (key_hash)
);
create index idx_api_key_merchant on merchant_api_keys (merchant_id);

-- 3. Payments: capture time, refunded amount, last provider status check.
alter table payment_intents add column captured_at timestamp(6) with time zone;
alter table payment_intents add column refunded_amount numeric(19, 4) default 0 not null;
alter table payment_intents add column reversed_amount numeric(19, 4) default 0 not null;
alter table payment_intents add column last_provider_check_at timestamp(6) with time zone;
-- Capture time comes from the audit trail (every V1 state change was audited), falling back to the
-- last update. A V1 payment awaiting reversal may or may not have been captured; the audit trail says which.
update payment_intents p set captured_at = coalesce(
        (select min(a.created_at) from audit_events a
         where a.payment_intent_id = p.id and a.event_type = 'TRANSACTION_STATE_CHANGED' and a.new_state = 'SUCCESS'),
        p.updated_at)
where p.state in ('SUCCESS', 'SETTLED', 'RECONCILED', 'REFUND_REQUESTED', 'REFUNDED')
   or (p.state = 'MANUAL_REVIEW' and p.state_before_review in ('SUCCESS', 'SETTLED'))
   or (p.state = 'REVERSAL_REQUIRED' and (
        exists (select 1 from audit_events a
                where a.payment_intent_id = p.id and a.event_type = 'TRANSACTION_STATE_CHANGED' and a.new_state = 'SUCCESS')
        or exists (select 1 from payment_transactions t where t.payment_intent_id = p.id and t.settled = true)));
update payment_intents set refunded_amount = amount where state = 'REFUNDED';
-- REFUND_REQUESTED is no longer a payment state: refunds (including partial ones) live on refund_requests.
update payment_intents p set state = case
        when exists (select 1 from payment_transactions t where t.payment_intent_id = p.id and t.settled = true)
            then 'SETTLED'
        else 'SUCCESS' end
where state = 'REFUND_REQUESTED';
create index idx_payment_intent_state_updated on payment_intents (state, updated_at);
alter table payment_intents add constraint ck_payment_intent_refunded check (
    refunded_amount >= 0 and reversed_amount >= 0 and refunded_amount + reversed_amount <= amount);

-- 4. Refunds: partial, idempotent per payment, executed by the provider through the outbox.
alter table refund_requests add column idempotency_key varchar(100);
alter table refund_requests add column requested_by varchar(150);
alter table refund_requests add column provider_refund_id varchar(100);
alter table refund_requests add column failure_reason varchar(500);
alter table refund_requests add column completed_at timestamp(6) with time zone;
update refund_requests set requested_by = 'merchant-api';
update refund_requests set completed_at = updated_at where status = 'COMPLETED';
alter table refund_requests alter column requested_by set not null;
alter table refund_requests add constraint uk_refund_idempotency unique (payment_intent_id, idempotency_key);

-- 5. Webhook inbox: events arrive by webhook or are recovered by polling the provider.
alter table provider_events add column source varchar(16) default 'WEBHOOK' not null;

-- 6. Review cases: money-moving decisions need a second operator (maker-checker).
alter table review_cases alter column status set data type varchar(32);
alter table review_cases alter column created_by set data type varchar(150);
alter table review_cases alter column resolved_by set data type varchar(150);
alter table review_cases add column proposed_action varchar(32);
alter table review_cases add column proposed_by varchar(150);
alter table review_cases add column proposed_at timestamp(6) with time zone;
alter table review_cases add column proposal_note varchar(1000);
alter table review_cases add column approved_by varchar(150);
alter table review_cases drop constraint ck_review_case_resolution;
alter table review_cases add constraint ck_review_case_resolution check (
    (status = 'OPEN' and resolved_at is null and proposed_action is null)
    or (status = 'AWAITING_APPROVAL' and resolved_at is null and proposed_action is not null and proposed_by is not null)
    or (status = 'RESOLVED' and resolved_at is not null and resolved_by is not null and resolution_action is not null));
-- At most one unresolved case per subject from now on (PostgreSQL enforces it with a partial unique index in V4).
-- Earlier versions deduplicated by lookup only, so concurrent writers may have opened duplicates: keep the oldest.
update review_cases c set status = 'RESOLVED', resolved_at = current_timestamp, resolved_by = 'migration',
    resolution_action = 'ACKNOWLEDGE',
    resolution_note = 'Closed during upgrade: duplicate of an older open case for the same subject.'
where c.status <> 'RESOLVED'
  and exists (select 1 from review_cases o
              where o.status <> 'RESOLVED' and o.reason = c.reason and o.reference_type = c.reference_type
                and o.reference_id = c.reference_id
                and (o.created_at < c.created_at or (o.created_at = c.created_at and o.id < c.id)));

-- 7. Settlement items: captures add, refunds and reversals deduct, so payouts are net.
alter table settlement_batch_items add column item_type varchar(16) default 'CAPTURE' not null;
alter table settlement_batch_items add column payment_intent_id uuid;
alter table settlement_batch_items add column refund_id uuid;
alter table settlement_batch_items add column reversal_payment_intent_id uuid;
alter table settlement_batch_items add column currency varchar(3);
update settlement_batch_items i set payment_intent_id =
    (select t.payment_intent_id from payment_transactions t where t.id = i.transaction_id);
update settlement_batch_items i set currency = (select b.currency from settlement_batches b where b.id = i.batch_id);
alter table settlement_batch_items alter column currency set not null;
alter table settlement_batch_items alter column payment_intent_id set not null;
alter table settlement_batch_items alter column transaction_id drop not null;
alter table settlement_batch_items add constraint fk_settlement_item_payment foreign key (payment_intent_id) references payment_intents (id);
alter table settlement_batch_items add constraint fk_settlement_item_refund foreign key (refund_id) references refund_requests (id);
alter table settlement_batch_items add constraint uk_settlement_item_refund unique (refund_id);
alter table settlement_batch_items add constraint uk_settlement_item_reversal unique (reversal_payment_intent_id);
-- A payment's capture is paid out once, whatever provider transaction rows it has (a chargeback can arrive
-- under a new provider transaction id). Existing items: the first one per payment is its capture.
alter table settlement_batch_items add column capture_payment_intent_id uuid;
update settlement_batch_items i set capture_payment_intent_id = i.payment_intent_id
where not exists (select 1 from settlement_batch_items j
                  where j.payment_intent_id = i.payment_intent_id
                    and (j.created_at < i.created_at or (j.created_at = i.created_at and j.id < i.id)));
alter table settlement_batch_items add constraint uk_settlement_item_capture unique (capture_payment_intent_id);
alter table settlement_batch_items add constraint ck_settlement_item_capture_payment check (
    item_type = 'CAPTURE' or capture_payment_intent_id is null);
alter table settlement_batch_items add constraint ck_settlement_item_reference check (
    (item_type = 'CAPTURE' and transaction_id is not null and refund_id is null and reversal_payment_intent_id is null and amount > 0)
    or (item_type = 'REFUND' and refund_id is not null and transaction_id is null and reversal_payment_intent_id is null and amount < 0)
    or (item_type = 'REVERSAL' and reversal_payment_intent_id is not null and transaction_id is null and refund_id is null and amount < 0));
create index idx_settlement_item_payment on settlement_batch_items (payment_intent_id);
alter table settlement_batches add column capture_amount numeric(19, 4) default 0 not null;
alter table settlement_batches add column deduction_amount numeric(19, 4) default 0 not null;
update settlement_batches set capture_amount = total_amount;
alter table settlement_batches add constraint ck_settlement_batch_net check (
    total_amount > 0 and total_amount = capture_amount - deduction_amount and deduction_amount >= 0);

-- 8. Double-entry ledger. A journal is posted once per (type, reference) and its postings sum to zero.
create table ledger_journals (
    id                uuid                        not null primary key,
    journal_type      varchar(32)                 not null,
    reference         varchar(100)                not null,
    payment_intent_id uuid,
    currency          varchar(3)                  not null,
    description       varchar(500)                not null,
    request_id        varchar(64),
    created_at        timestamp(6) with time zone not null,
    constraint uk_ledger_journal_reference unique (journal_type, reference)
);
create index idx_ledger_journal_payment on ledger_journals (payment_intent_id);
create table ledger_postings (
    id         uuid                        not null primary key,
    journal_id uuid                        not null,
    account    varchar(150)                not null,
    currency   varchar(3)                  not null,
    amount     numeric(19, 4)              not null,
    created_at timestamp(6) with time zone not null,
    constraint fk_ledger_posting_journal foreign key (journal_id) references ledger_journals (id),
    constraint ck_ledger_posting_nonzero check (amount <> 0)
);
create index idx_ledger_posting_account on ledger_postings (account, currency);
create index idx_ledger_posting_journal on ledger_postings (journal_id);

-- 9. Transactional outbox: provider calls are recorded with the state change, then executed by leased workers.
create table outbox_commands (
    id                 uuid                        not null primary key,
    version            bigint                      not null,
    command_type       varchar(32)                 not null,
    payment_intent_id  uuid                        not null,
    reference_id       varchar(100)                not null,
    idempotency_key    varchar(100)                not null,
    status             varchar(16)                 not null,
    attempts           integer                     not null,
    next_attempt_at    timestamp(6) with time zone not null,
    lease_owner        varchar(100),
    lease_until        timestamp(6) with time zone,
    last_error         varchar(1000),
    provider_reference varchar(100),
    -- Set, and committed, before the first call leaves: from then on the provider may have acted, so the
    -- command can no longer be cancelled and every later attempt asks the provider before resending.
    dispatched_at      timestamp(6) with time zone,
    -- The provider transaction the call is about, fixed at first dispatch so every resend is the same request.
    provider_transaction_id varchar(100),
    created_at         timestamp(6) with time zone not null,
    updated_at         timestamp(6) with time zone not null,
    constraint uk_outbox_idempotency_key unique (idempotency_key),
    constraint fk_outbox_payment foreign key (payment_intent_id) references payment_intents (id)
);
create index idx_outbox_due on outbox_commands (status, next_attempt_at);
create index idx_outbox_payment on outbox_commands (payment_intent_id);

-- 10. Stored responses for Idempotency-Key replays.
create table idempotency_records (
    id                    uuid                        not null primary key,
    scope                 varchar(150)                not null,
    idempotency_key       varchar(100)                not null,
    request_method        varchar(10)                 not null,
    request_path          varchar(300)                not null,
    request_hash          varchar(64)                 not null,
    status                varchar(16)                 not null,
    response_status       integer,
    response_content_type varchar(100),
    response_location     varchar(300),
    response_body         varchar(1000000),
    created_at            timestamp(6) with time zone not null,
    completed_at          timestamp(6) with time zone,
    expires_at            timestamp(6) with time zone not null,
    constraint uk_idempotency_scope_key unique (scope, idempotency_key)
);
create index idx_idempotency_expiry on idempotency_records (expires_at);

-- 11. Audit hash chains (columns filled by V3, then made mandatory).
alter table audit_events alter column actor set data type varchar(150);
alter table audit_events add column chain_key varchar(150);
alter table audit_events add column sequence_number bigint;
alter table audit_events add column previous_hash varchar(64);
alter table audit_events add column event_hash varchar(64);

-- 12. Provider simulator: a local stand-in for the external payment provider, with its own tables.
create table sim_payments (
    payment_intent_id       uuid                        not null primary key,
    provider_transaction_id varchar(100)                not null,
    status                  varchar(32)                 not null,
    amount                  numeric(19, 4)              not null,
    currency                varchar(3)                  not null,
    refunded_amount         numeric(19, 4)              not null,
    created_at              timestamp(6) with time zone not null,
    updated_at              timestamp(6) with time zone not null,
    constraint uk_sim_payment_tx unique (provider_transaction_id)
);
create table sim_operations (
    id                      uuid                        not null primary key,
    operation_type          varchar(16)                 not null,
    idempotency_key         varchar(100)                not null,
    request_hash            varchar(64)                 not null,
    provider_transaction_id varchar(100)                not null,
    amount                  numeric(19, 4)              not null,
    currency                varchar(3)                  not null,
    status                  varchar(16)                 not null,
    failure_reason          varchar(300),
    created_at              timestamp(6) with time zone not null,
    constraint uk_sim_operation_key unique (operation_type, idempotency_key)
);
create table sim_webhook_deliveries (
    id                   uuid                        not null primary key,
    event_id             varchar(100)                not null,
    payment_intent_id    uuid                        not null,
    payload              varchar(4000)               not null,
    status               varchar(16)                 not null,
    attempts             integer                     not null,
    next_attempt_at      timestamp(6) with time zone not null,
    last_response_status integer,
    last_error           varchar(500),
    created_at           timestamp(6) with time zone not null,
    updated_at           timestamp(6) with time zone not null
);
create index idx_sim_delivery_due on sim_webhook_deliveries (status, next_attempt_at);
