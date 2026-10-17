-- Device risk as money (ADR 028): one decision per payment, with the rule and the points behind it.

alter table payment_intents add column risk_action varchar(32) default 'PHYSICAL_GOODS' not null;
alter table payment_intents add column required_confirmation varchar(16);
alter table payment_intents add column confirmed_at timestamp(6) with time zone;
alter table payment_intents add column confirmation_method varchar(16);

create table risk_decisions (
    id                      uuid                        not null primary key,
    version                 bigint                      not null,
    payment_intent_id       uuid                        not null,
    merchant_id             varchar(100)                not null,
    merchant_reference      varchar(100)                not null,
    amount                  numeric(19, 4)              not null,
    currency                varchar(3)                  not null,
    risk_action             varchar(32)                 not null,
    decision                varchar(32)                 not null,
    rule                    varchar(32)                 not null,
    device_points           integer                     not null,
    amount_points           integer                     not null,
    action_points           integer                     not null,
    missing_evidence_points integer                     not null,
    total_points            integer                     not null,
    severe_signal           boolean                     not null,
    device_level            varchar(16),
    reported_level          varchar(16),
    evidence_present        boolean                     not null,
    simulated_evidence      boolean                     not null,
    signals                 varchar(1000),
    required_confirmation   varchar(16)                 not null,
    outcome                 varchar(32)                 not null,
    decided_at              timestamp(6) with time zone not null,
    outcome_at              timestamp(6) with time zone,
    label                   varchar(16),
    label_source            varchar(32),
    labelled_at             timestamp(6) with time zone,
    constraint uk_risk_decision_payment unique (payment_intent_id),
    constraint fk_risk_decision_payment foreign key (payment_intent_id) references payment_intents (id),
    constraint ck_risk_decision_points check (total_points = device_points + amount_points + action_points + missing_evidence_points)
);
create index idx_risk_decision_decided on risk_decisions (decided_at);
create index idx_risk_decision_reference on risk_decisions (merchant_id, merchant_reference);
