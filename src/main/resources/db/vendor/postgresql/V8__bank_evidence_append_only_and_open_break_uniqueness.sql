-- PostgreSQL-only protections for the bank leg (H2 has no equivalent; see db/vendor/h2/README.md).

-- Bank statements are evidence: kept verbatim, never edited or deleted without the maintenance opt-in.
create trigger bank_statements_append_only
    before update or delete on bank_statements
    for each row execute function payflow_reject_evidence_change();

create trigger bank_statements_no_truncate
    before truncate on bank_statements
    for each statement execute function payflow_reject_evidence_change();

-- At most one open break per (category, subject), even when the import and the reconciliation job race.
create unique index uk_reconciliation_break_open
    on reconciliation_breaks (category, subject_key)
    where status = 'OPEN';
