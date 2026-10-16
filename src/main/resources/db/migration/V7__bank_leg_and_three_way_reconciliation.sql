-- Settlement certainty: the bank leg and three-way reconciliation (ADR 025).

-- 1. Payout lifecycle. A batch is SENT when created, IN_TRANSIT once the provider accepts the payout, LANDED
--    only when a bank credit matched by reference, amount and value-date window is booked, or BROKEN with a
--    reason. Batches paid out before the bank leg existed are LEGACY: they have no bank evidence to match.
alter table settlement_batches add column version bigint default 0 not null;
alter table settlement_batches add column payout_status varchar(16) default 'LEGACY' not null;
alter table settlement_batches add column payout_reference varchar(35);
alter table settlement_batches add column broken_reason varchar(32);
alter table settlement_batches add column provider_payout_id varchar(100);
alter table settlement_batches add column provider_fee_amount numeric(19, 4);
alter table settlement_batches add column expected_net_amount numeric(19, 4);
alter table settlement_batches add column expected_value_date date;
alter table settlement_batches add column landed_amount numeric(19, 4);
alter table settlement_batches add column bank_charges_amount numeric(19, 4);
alter table settlement_batches add column landed_value_date date;
alter table settlement_batches add column bank_line_id uuid;
alter table settlement_batches add column returned_amount numeric(19, 4);
alter table settlement_batches add column in_transit_at timestamp(6) with time zone;
alter table settlement_batches add column landed_at timestamp(6) with time zone;
alter table settlement_batches add column broken_at timestamp(6) with time zone;
alter table settlement_batches add constraint uk_settlement_batch_payout_reference unique (payout_reference);
alter table settlement_batches add constraint ck_settlement_batch_payout check (
    payout_status in ('LEGACY', 'SENT', 'IN_TRANSIT', 'LANDED', 'BROKEN')
    and (payout_status = 'LEGACY' or payout_reference is not null)
    and (payout_status <> 'BROKEN' or broken_reason is not null)
    and (payout_status <> 'LANDED' or (landed_amount is not null and bank_line_id is not null)));

-- 2. Outbox: a payout is a provider call about a settlement batch, not about one payment.
alter table outbox_commands alter column payment_intent_id drop not null;
alter table outbox_commands add column settlement_batch_id uuid;
alter table outbox_commands add constraint fk_outbox_settlement_batch
    foreign key (settlement_batch_id) references settlement_batches (id);
alter table outbox_commands add constraint ck_outbox_subject check (
    (command_type = 'PAYOUT' and settlement_batch_id is not null and payment_intent_id is null)
    or (command_type <> 'PAYOUT' and payment_intent_id is not null and settlement_batch_id is null));
create index idx_outbox_batch on outbox_commands (settlement_batch_id);

-- 3. Payments: the provider's settlement report confirmed the row. RECONCILED also needs the batch to land.
alter table payment_intents add column provider_report_matched_at timestamp(6) with time zone;

-- 4. Bank statements as received (the evidence, kept verbatim) and their lines.
create table bank_statements (
    id              uuid                        not null primary key,
    statement_id    varchar(35)                 not null,
    account_id      varchar(70)                 not null,
    currency        varchar(3)                  not null,
    sequence_number bigint                      not null,
    statement_date  date                        not null,
    opening_balance numeric(19, 4)              not null,
    closing_balance numeric(19, 4)              not null,
    entry_count     integer                     not null,
    content_hash    varchar(64)                 not null,
    content         varchar(1000000)            not null,
    imported_by     varchar(150)                not null,
    imported_at     timestamp(6) with time zone not null,
    constraint uk_bank_statement_id unique (statement_id),
    -- A second statement claiming an already imported sequence number is a conflict, never a second set of lines.
    constraint uk_bank_statement_sequence unique (account_id, sequence_number)
);

-- A line is identified by its content hash, which includes the bank's own entry reference: the same entry
-- delivered again (in a re-sent or re-issued statement) is skipped; a genuine second credit is a new line.
create table bank_statement_lines (
    id                   uuid                        not null primary key,
    version              bigint                      not null,
    statement_id         varchar(35)                 not null,
    line_hash            varchar(64)                 not null,
    account_id           varchar(70)                 not null,
    currency             varchar(3)                  not null,
    amount               numeric(19, 4)              not null,
    credit_debit         varchar(4)                  not null,
    reversal             boolean                     not null,
    booking_date         date                        not null,
    value_date           date                        not null,
    account_servicer_ref varchar(35)                 not null,
    end_to_end_id        varchar(35),
    remittance_info      varchar(140),
    instructed_amount    numeric(19, 4),
    charges_amount       numeric(19, 4),
    match_status         varchar(16)                 not null,
    settlement_batch_id  uuid,
    imported_at          timestamp(6) with time zone not null,
    constraint uk_bank_line_hash unique (line_hash),
    constraint ck_bank_line_amount check (amount > 0),
    constraint ck_bank_line_direction check (credit_debit in ('CRDT', 'DBIT')),
    constraint ck_bank_line_match check (match_status in ('MATCHED', 'SUSPENSE', 'RETURN', 'CLEARED'))
);
create index idx_bank_line_reference on bank_statement_lines (end_to_end_id);
create index idx_bank_line_batch on bank_statement_lines (settlement_batch_id);

-- Per account: which statements have arrived without a gap, counting from the first sequence number the
-- platform expects. A credit can only be called missing once every statement up to the end of its value-date
-- window has been imported, whatever order they arrived in. covered_through is null until the first one is in.
create table bank_accounts (
    account_id       varchar(70)                 not null primary key,
    version          bigint                      not null,
    currency         varchar(3)                  not null,
    first_sequence   bigint                      not null,
    covered_sequence bigint                      not null,
    covered_through  date,
    last_imported_at timestamp(6) with time zone not null
);

-- 5. Breaks: every disagreement between the ledger, the provider and the bank, with an owner and a due time.
create table reconciliation_breaks (
    id                  uuid                        not null primary key,
    version             bigint                      not null,
    category            varchar(32)                 not null,
    owner               varchar(32)                 not null,
    status              varchar(16)                 not null,
    subject_key         varchar(100)                not null,
    settlement_batch_id uuid,
    bank_line_id        uuid,
    statement_id        varchar(35),
    currency            varchar(3),
    amount              numeric(19, 4),
    -- Signed amount this break holds in the bank_suspense account (credits positive). Zero for breaks about
    -- money that never arrived. The ledger check proves bank_suspense equals the sum over open breaks.
    suspense_amount     numeric(19, 4)              not null,
    detail              varchar(1000)               not null,
    opened_at           timestamp(6) with time zone not null,
    due_at              timestamp(6) with time zone not null,
    resolved_at         timestamp(6) with time zone,
    resolved_by         varchar(150),
    resolution          varchar(32),
    resolution_note     varchar(1000),
    constraint ck_break_status check (
        (status = 'OPEN' and resolved_at is null and resolution is null)
        or (status = 'RESOLVED' and resolved_at is not null and resolution is not null and resolved_by is not null))
);
create index idx_break_status_due on reconciliation_breaks (status, due_at);
create index idx_break_batch on reconciliation_breaks (settlement_batch_id);

-- 6. The provider's own word on each payout, from its settlement report.
create table provider_payout_reports (
    id                  uuid                        not null primary key,
    settlement_batch_id uuid                        not null,
    report_date         date                        not null,
    provider_payout_id  varchar(100)                not null,
    gross_amount        numeric(19, 4)              not null,
    fee_amount          numeric(19, 4)              not null,
    net_amount          numeric(19, 4)              not null,
    currency            varchar(3)                  not null,
    value_date          date,
    consistent          boolean                     not null,
    imported_by         varchar(150)                not null,
    imported_at         timestamp(6) with time zone not null,
    constraint uk_provider_payout_report_batch unique (settlement_batch_id),
    constraint fk_provider_payout_report_batch foreign key (settlement_batch_id) references settlement_batches (id)
);

-- 7. Provider simulator: payouts it executed (idempotent by key) and which transactions each one settled.
create table sim_payouts (
    id              varchar(40)                 not null primary key,
    idempotency_key varchar(150)                not null,
    request_hash    varchar(64)                 not null,
    merchant_id     varchar(100)                not null,
    reference       varchar(35)                 not null,
    gross_amount    numeric(19, 4)              not null,
    fee_amount      numeric(19, 4)              not null,
    net_amount      numeric(19, 4)              not null,
    currency        varchar(3)                  not null,
    payout_date     date                        not null,
    value_date      date                        not null,
    status          varchar(16)                 not null,
    failure_reason  varchar(300),
    created_at      timestamp(6) with time zone not null,
    constraint uk_sim_payout_key unique (idempotency_key)
);
create table sim_payout_items (
    payout_id               varchar(40)  not null,
    provider_transaction_id varchar(100) not null,
    constraint pk_sim_payout_items primary key (payout_id, provider_transaction_id)
);

-- 8. Bank simulator: the platform's settlement account at a bank, its entries and the statements it issues.
create table bank_sim_accounts (
    account_id    varchar(70)    not null primary key,
    currency      varchar(3)     not null,
    business_date date           not null,
    next_sequence bigint         not null,
    balance       numeric(19, 4) not null
);
create table bank_sim_entries (
    id                   uuid                        not null primary key,
    account_id           varchar(70)                 not null,
    currency             varchar(3)                  not null,
    amount               numeric(19, 4)              not null,
    credit_debit         varchar(4)                  not null,
    reversal             boolean                     not null,
    booking_date         date                        not null,
    value_date           date                        not null,
    account_servicer_ref varchar(35)                 not null,
    end_to_end_id        varchar(35),
    remittance_info      varchar(140),
    debtor_name          varchar(140),
    instructed_amount    numeric(19, 4),
    charges_amount       numeric(19, 4),
    statement_id         varchar(35),
    created_at           timestamp(6) with time zone not null,
    constraint uk_bank_sim_entry_ref unique (account_servicer_ref)
);
create index idx_bank_sim_entry_booking on bank_sim_entries (account_id, booking_date);
create table bank_sim_statements (
    statement_id    varchar(35)                 not null primary key,
    account_id      varchar(70)                 not null,
    sequence_number bigint                      not null,
    statement_date  date                        not null,
    content         varchar(1000000)            not null,
    deliveries      integer                     not null,
    created_at      timestamp(6) with time zone not null
);
