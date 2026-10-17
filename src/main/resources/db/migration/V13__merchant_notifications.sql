-- Merchant notifications (ADR 030): where each shop wants them, and every event, written in the transaction of the
-- change it announces. Events are numbered per payment so a shop can put out-of-order deliveries back in order.

create table merchant_webhook_endpoints (
    merchant_id            varchar(100)                not null primary key,
    version                bigint                      not null,
    url                    varchar(500)                not null,
    secret                 varchar(100)                not null,
    previous_secret        varchar(100),
    previous_secret_until  timestamp(6) with time zone,
    enabled                boolean                     not null,
    created_at             timestamp(6) with time zone not null,
    updated_at             timestamp(6) with time zone not null,
    constraint fk_webhook_endpoint_merchant foreign key (merchant_id) references merchants (merchant_id)
);

create table merchant_events (
    id                 uuid                        not null primary key,
    version            bigint                      not null,
    merchant_id        varchar(100)                not null,
    event_type         varchar(40)                 not null,
    payment_intent_id  uuid                        not null,
    refund_id          uuid,
    sequence           bigint                      not null,
    state              varchar(32)                 not null,
    payload            varchar(4000)               not null,
    status             varchar(16)                 not null,
    attempts           integer                     not null,
    next_attempt_at    timestamp(6) with time zone,
    lease_until        timestamp(6) with time zone,
    last_status_code   integer,
    last_error         varchar(1000),
    delivered_at       timestamp(6) with time zone,
    created_at         timestamp(6) with time zone not null,
    constraint uk_merchant_event_sequence unique (payment_intent_id, sequence),
    constraint fk_merchant_event_merchant foreign key (merchant_id) references merchants (merchant_id),
    constraint fk_merchant_event_payment foreign key (payment_intent_id) references payment_intents (id)
);
create index idx_merchant_event_due on merchant_events (status, next_attempt_at);
create index idx_merchant_event_merchant on merchant_events (merchant_id, created_at);
