-- Slowness as money (ADR 027): when a payment was handed to the provider, and when and why it was withdrawn.

-- The confirmation SLO runs from start to capture.
alter table payment_intents add column started_at timestamp(6) with time zone;
-- Payments started before this column existed: the audit trail has the moment they became PENDING.
update payment_intents p set started_at = (
        select min(a.created_at) from audit_events a
        where a.payment_intent_id = p.id and a.event_type = 'TRANSACTION_STATE_CHANGED' and a.new_state = 'PENDING')
where p.state <> 'CREATED';

-- Withdrawn before the customer paid, for example an abandoned checkout.
alter table payment_intents add column cancelled_at timestamp(6) with time zone;
alter table payment_intents add column cancellation_reason varchar(32);
alter table payment_intents add constraint ck_payment_intent_cancellation check (
    (state = 'CANCELLED' and cancelled_at is not null and cancellation_reason is not null)
    or (state <> 'CANCELLED' and cancelled_at is null and cancellation_reason is null));
create index idx_payment_intent_cancelled on payment_intents (cancellation_reason, cancelled_at);
