-- PostgreSQL-only protections (H2, used for fast tests, has no equivalent).

-- Evidence tables are append-only: updates, deletes and truncation are refused unless a maintenance
-- transaction explicitly opts in (SET LOCAL payflow.evidence_maintenance = 'on').
create or replace function payflow_reject_evidence_change() returns trigger
language plpgsql as $$
begin
    if coalesce(current_setting('payflow.evidence_maintenance', true), 'off') = 'on' then
        return coalesce(new, old);
    end if;
    raise exception 'payflow: % on % is not allowed; evidence is append-only', tg_op, tg_table_name
        using errcode = '55000';
end;
$$;

create trigger audit_events_append_only
    before update or delete on audit_events
    for each row execute function payflow_reject_evidence_change();

create trigger ledger_journals_append_only
    before update or delete on ledger_journals
    for each row execute function payflow_reject_evidence_change();

create trigger ledger_postings_append_only
    before update or delete on ledger_postings
    for each row execute function payflow_reject_evidence_change();

-- At most one unresolved case per (reason, subject), even under concurrent writers.
create trigger audit_events_no_truncate
    before truncate on audit_events
    for each statement execute function payflow_reject_evidence_change();

create trigger ledger_journals_no_truncate
    before truncate on ledger_journals
    for each statement execute function payflow_reject_evidence_change();

create trigger ledger_postings_no_truncate
    before truncate on ledger_postings
    for each statement execute function payflow_reject_evidence_change();

create unique index uk_review_case_unresolved
    on review_cases (reason, reference_type, reference_id)
    where status <> 'RESOLVED';
