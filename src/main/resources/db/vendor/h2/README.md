H2-specific migrations go here. H2 is only used for fast tests; the PostgreSQL-only protections in
`../postgresql` (append-only evidence triggers, partial unique indexes on unresolved review cases and on
open reconciliation breaks) have no H2 equivalent and are covered by the PostgreSQL CI job.
