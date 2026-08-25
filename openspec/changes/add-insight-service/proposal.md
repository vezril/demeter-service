## Why

`build-core-pipeline` listed "No UI; Home Assistant automations own presentation" as an explicit non-goal. This change reverses that, on evidence rather than appetite.

The service now runs unattended on a NAS and its entire observable surface is `kubectl logs`. Three defects from the first real runs make the case:

- The run reported `demeter_alerts_delivered 0.0` while messages were arriving at the broker. It was visible only by comparing a log line against the broker's own counter, and had it gone unnoticed the alert ledger would never have been written, so dedup would have re-alerted every deal every day for as long as each flyer ran.
- Ten alerts were published to a topic with zero subscribers. Every delivery succeeded; nobody could read them.
- Tuning one watch (butter) to eighteen exclusion terms was done by reading raw output, because there was no way to ask "what matched, and why was it suppressed?"

None of these were failures of the pipeline. They were failures of visibility, and each was found by hand at a cost far exceeding the read query that would have shown it.

Demeter also holds data nothing currently displays: 19,626 price observations across 18,678 products, with confidence-weighted rolling statistics and deal verdicts already implemented and mutation-tested. The question the whole system exists to answer — "is this actually cheap?" — is answerable today only by an alert firing or not firing.

## What Changes

- Add a **read-only** insight service, separate from `demeter-service`, that reads the existing PostgreSQL schema and exposes it over HTTP.
- Add a browser UI over that API for four views: product price history, the last run's report, per-watch health, and alert history.
- Grant the new service its own PostgreSQL role with `SELECT` only. It must be structurally incapable of writing.
- Persist the run report. `demeter-service` IS modified for this, contrary to this proposal's first draft: the report existed only as a log line, so `GET /v1/runs/latest` had nothing to read. It still binds no socket and the daily run keeps its own failure domain — the change is one table, one store, and one call at the end of a run.

Delivered in two phases with a gate between them, because phase 1 is independently useful and answers the design question phase 2 depends on:

- **Phase 1** — the API alone, no frontend. Replaces the `kubectl logs` habit and proves the read-only-service shape.
- **Phase 2** — the UI, gated on phase 1 running for at least one real daily cycle.

## Capabilities

### New Capabilities

- `insight-api`: A read-only HTTP surface over the price history: per-product observation series with rolling statistics and verdict, the latest run report, per-watch match/suppression/alert counts, and alert history. Reuses `PriceStats` and `DealVerdict` rather than reimplementing them, so the numbers the UI shows are computed by the code the mutation gate already covers.
- `insight-ui`: A browser interface over `insight-api`. Read-only, tailnet-only, four views. Presentation only — no analytics of its own, so the maths cannot drift between the two services.

### Modified Capabilities

- `persistence`: adds the `run_report` table — one row per completed run, holding the counts, the per-reason suppression map, the alert audience (NULL for "could not tell", which is not zero), degraded sources and failures.
- `orchestration`: adds `RunReportStore` and writes the report at the end of each run. A write failure is warned about, never raised: the run already happened, and losing the bookkeeping is a far smaller loss than failing a fetch that cannot be repeated.

The first draft claimed no modification to `demeter-service`. That was wrong, and it was wrong in a way worth recording: task 1.4 (`GET /v1/runs/latest`) was listed first while the table it reads was deferred to 3.1. Flyers listed, observations and alerts are derivable from existing tables, but matches, items parsed/dropped, decode failure rate, elapsed time and — most importantly — the per-reason suppression breakdown exist nowhere but stdout. Deriving a partial report would have produced a view that looks complete while omitting the fields that make it worth having.

## Impact

- **Code**: two new sbt modules (`insight` for the API, plus a frontend project). `foundations`, `persistence`, and `pricehistory` are consumed unchanged; no existing module is edited.
- **Schema**: no migrations. The read model is the existing tables. `product_key` is the join that makes history coherent across merchants; `price_observation` already carries `effective_cents`, `unit_cents`, `valid_from`/`valid_to`, `price_confidence` and `sale_text`.
- **Deployment**: a second chart, or a second release of the existing one. Tailnet-only, following the pattern used for the HermesMQ console (`hermes.tailscale` via Traefik's NodePort).
- **Security**: this UI displays a household's grocery buying patterns. Not public, no auth story invented for it — reachable on the tailnet or not at all.
- **Boundaries unchanged**: facts only, no flyer imagery, personal use.
