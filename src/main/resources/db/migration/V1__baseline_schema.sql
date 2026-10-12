-- Payflow Reliability baseline schema.
-- Hibernate validates entities against this schema at startup (ddl-auto: validate);
-- it never changes the schema itself. Business invariants that must hold under
-- concurrency are enforced here, not only in application code.

create table payment_intents (
    id                  uuid                        primary key,
    version             bigint                      not null,
    merchant_id         varchar(100)                not null,
    merchant_reference  varchar(100)                not null,
    amount              numeric(19, 2)              not null,
    currency            varchar(3)                  not null,
    idempotency_key     varchar(100)                not null,
    state               varchar(32)                 not null,
    state_before_review varchar(32),
    created_at          timestamp(6) with time zone not null,
    updated_at          timestamp(6) with time zone not null,
    -- Idempotency keys are scoped per merchant; concurrent creates with one key cannot both insert.
    constraint uk_payment_intent_merchant_idempotency unique (merchant_id, idempotency_key),
    constraint ck_payment_intent_amount_positive check (amount > 0),
    -- A held payment always remembers where it was held from, and only a held payment does.
    constraint ck_payment_intent_review_state check (
        (state = 'MANUAL_REVIEW' and state_before_review is not null)
        or (state <> 'MANUAL_REVIEW' and state_before_review is null))
);
create index idx_payment_intent_merchant_reference on payment_intents (merchant_id, merchant_reference);
create index idx_payment_intent_state on payment_intents (state);

-- One row per provider transaction; its status follows the provider lifecycle.
create table payment_transactions (
    id                      uuid                        primary key,
    version                 bigint                      not null,
    payment_intent_id       uuid                        not null,
    merchant_id             varchar(100)                not null,
    provider_transaction_id varchar(100)                not null,
    provider_status         varchar(32)                 not null,
    amount                  numeric(19, 2)              not null,
    currency                varchar(3)                  not null,
    settled                 boolean                     not null,
    settled_on              date,
    created_at              timestamp(6) with time zone not null,
    updated_at              timestamp(6) with time zone not null,
    constraint fk_payment_tx_payment_intent foreign key (payment_intent_id) references payment_intents (id),
    constraint uk_payment_tx_provider_transaction unique (provider_transaction_id),
    constraint ck_payment_tx_settled_on check (
        (settled = true and settled_on is not null) or (settled = false and settled_on is null))
);
create index idx_payment_tx_payment_intent on payment_transactions (payment_intent_id);
create index idx_payment_tx_settleable on payment_transactions (merchant_id, settled, provider_status);

create table review_cases (
    id                uuid                        primary key,
    version           bigint                      not null,
    reason            varchar(64)                 not null,
    status            varchar(16)                 not null,
    reference_type    varchar(64)                 not null,
    reference_id      varchar(100)                not null,
    payment_intent_id uuid,
    details           varchar(1000)               not null,
    created_by        varchar(100)                not null,
    resolved_by       varchar(100),
    resolved_at       timestamp(6) with time zone,
    resolution_note   varchar(1000),
    resolution_action varchar(32),
    created_at        timestamp(6) with time zone not null,
    constraint fk_review_case_payment_intent foreign key (payment_intent_id) references payment_intents (id),
    constraint ck_review_case_resolution check (
        (status = 'OPEN' and resolved_at is null)
        or (status = 'RESOLVED' and resolved_at is not null and resolved_by is not null and resolution_action is not null))
);
create index idx_review_case_status_created on review_cases (status, created_at);
create index idx_review_case_reference on review_cases (reference_type, reference_id);
create index idx_review_case_payment_intent on review_cases (payment_intent_id);

-- Webhook inbox: every authenticated provider event exactly once, with its outcome.
-- payment_intent_id is what the provider claimed, so it deliberately has no foreign key.
create table provider_events (
    id                      uuid                        primary key,
    provider_event_id       varchar(100)                not null,
    provider_transaction_id varchar(100)                not null,
    payment_intent_id       uuid                        not null,
    status                  varchar(32)                 not null,
    amount                  numeric(19, 2)              not null,
    currency                varchar(3)                  not null,
    outcome                 varchar(32)                 not null,
    detail                  varchar(1000)               not null,
    review_case_id          uuid,
    received_at             timestamp(6) with time zone not null,
    constraint uk_provider_event_id unique (provider_event_id),
    constraint fk_provider_event_review_case foreign key (review_case_id) references review_cases (id)
);
create index idx_provider_event_payment_intent on provider_events (payment_intent_id, received_at);

create table settlement_batches (
    id                uuid                        primary key,
    merchant_id       varchar(100)                not null,
    transaction_count integer                     not null,
    total_amount      numeric(19, 2)              not null,
    currency          varchar(3)                  not null,
    created_at        timestamp(6) with time zone not null,
    constraint ck_settlement_batch_count_positive check (transaction_count > 0)
);
create index idx_settlement_batch_merchant on settlement_batches (merchant_id, created_at);

create table settlement_batch_items (
    id             uuid                        primary key,
    batch_id       uuid                        not null,
    transaction_id uuid                        not null,
    amount         numeric(19, 2)              not null,
    created_at     timestamp(6) with time zone not null,
    constraint fk_settlement_item_batch foreign key (batch_id) references settlement_batches (id),
    constraint fk_settlement_item_transaction foreign key (transaction_id) references payment_transactions (id),
    -- A transaction is paid out at most once, even if two settlement runs race.
    constraint uk_settlement_item_transaction unique (transaction_id)
);
create index idx_settlement_item_batch on settlement_batch_items (batch_id);

create table refund_requests (
    id                uuid                        primary key,
    version           bigint                      not null,
    payment_intent_id uuid                        not null,
    amount            numeric(19, 2)              not null,
    currency          varchar(3)                  not null,
    reason            varchar(500)                not null,
    status            varchar(32)                 not null,
    created_at        timestamp(6) with time zone not null,
    updated_at        timestamp(6) with time zone not null,
    constraint fk_refund_payment_intent foreign key (payment_intent_id) references payment_intents (id),
    constraint ck_refund_amount_positive check (amount > 0)
);
create index idx_refund_payment_intent on refund_requests (payment_intent_id);
create index idx_refund_status on refund_requests (status);

-- Append-only evidence. payment_intent_id has no foreign key so audit rows never block or
-- depend on the lifecycle of the records they describe.
create table audit_events (
    id                uuid                        primary key,
    aggregate_type    varchar(64)                 not null,
    aggregate_id      varchar(100)                not null,
    payment_intent_id uuid,
    event_type        varchar(64)                 not null,
    previous_state    varchar(32),
    new_state         varchar(32),
    actor             varchar(100)                not null,
    reason            varchar(1000)               not null,
    metadata          varchar(2000)               not null,
    request_id        varchar(64),
    created_at        timestamp(6) with time zone not null
);
create index idx_audit_aggregate on audit_events (aggregate_type, aggregate_id, created_at);
create index idx_audit_payment_intent on audit_events (payment_intent_id, created_at);
