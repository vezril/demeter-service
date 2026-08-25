## 0. Prerequisite — persist the run report (was 3.1)

Moved ahead of 1.4, which cannot be built without it: the report existed only as
a log line. Modifies `demeter-service`, which this proposal originally said it
would not.

- [x] 0.1 `run_report` table: counts, per-reason suppression map as jsonb, alert audience nullable (NULL is "could not tell", not zero), degraded sources, failures, partial flag
- [x] 0.2 `RunReportStore` + `DoobieRunReportStore` in `orchestration` (RunReport is an 08 type that 03 cannot see, mirroring where the alert ledger lives)
- [x] 0.3 Write at the end of every run; a store failure is warned, never raised
- [x] 0.4 `@boundary` round-trip tests against real Postgres: suppression map survives as a map, unknown audience stays unknown and zero stays zero, `latest` orders by finished_at rather than insertion

## 1. Phase 1 — the API alone (no frontend)

- [ ] 1.1 `modules/insight` skeleton: sbt module depending on `foundations`, `persistence`, `pricehistory`; http4s ember server; config from environment, validated fail-fast like `orchestration`
- [ ] 1.2 Read-only role: `CREATE ROLE demeter_read` with `GRANT SELECT`, documented in the chart; test asserts a write is refused by the database rather than by the application
- [ ] 1.3 Query layer owned by this module (not importing demeter's stores wholesale), so a schema change breaks one file loudly at compile time
- [ ] 1.4 `GET /v1/runs/latest` — the first endpoint, chosen because it replaces the `kubectl logs` habit immediately
- [ ] 1.5 `GET /v1/products/{productKey}/history` — series with per-point confidence, statistics from `PriceStats.rollingStats`, verdict from `DealVerdict`; test pins that the API's numbers equal the library's
- [ ] 1.6 `GET /v1/watches` and `GET /v1/alerts`
- [ ] 1.7 Health: not-ready when Postgres is unreachable; no stale cached answers
- [ ] 1.8 Chart + tailnet ingress following the `hermes.tailscale` pattern (empty ingress class — this cluster registers no IngressClass)
- [ ] 1.9 **Gate**: run against the live NAS database for at least one full daily cycle; confirm the run report matches the logged exposition exactly

## 2. Phase 2 — the UI

- [ ] 2.1 Decide repo placement (this repo vs its own) — see design.md Open Questions
- [ ] 2.2 Product history view: chart with rolling median behind the points, lower-confidence points visually distinct
- [ ] 2.3 Run report view, with suppression by reason and the matched/delivered/suppressed reconciliation
- [ ] 2.4 Watch health view: matched item names, so tuning is reading rather than grepping
- [ ] 2.5 Alert history view, linking each alert to its product's price series
- [ ] 2.6 Tailnet-only ingress; no public exposure, no authentication
- [ ] 2.7 **Gate**: tune one real watch end to end using only the UI

## 3. Deferred, deliberately

- [ ] 3.2 Prometheus exposition served by `insight-api` on demeter's behalf — attractive, but the metrics would describe a service other than the one serving them
