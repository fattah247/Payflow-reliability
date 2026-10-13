H2-specific migrations go here. H2 is only used for fast tests; the PostgreSQL-only protections in
`../postgresql` (append-only evidence triggers, partial unique index on unresolved review cases)
have no H2 equivalent and are covered by the PostgreSQL CI job.
