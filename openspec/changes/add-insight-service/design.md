## Context

`demeter-service` is a batch job. It wakes on an in-process cron, fetches, normalizes, persists, matches, decides, and pushes alerts outward. It binds no socket: no Service, no Ingress, no health endpoint. That is deliberate — a liveness probe on this service would prove the JVM answers HTTP, not that the daily run works — and the Helm chart says so.

Everything a UI would show already exists in PostgreSQL, and most of the analysis already exists in code. `PriceStats.rollingStats` computes a confidence-weighted trailing distribution; `PriceStats.weightedMedian` and `weightOf` pin how provenance affects the numbers; `DealVerdict` turns statistics into the phrases alerts already use ("cheapest in 8 weeks"). `ObservationStore.observationsFor(key, since)` is the exact query a price chart needs. `pricehistory` has 33 tests written from the 07.x Gherkin. None of this should be rewritten.

Worth noting while relying on it: `pricehistory` is `@pure` but is **not** in the mutation gate, which covers only `foundations` and `normalization`. Its tests are the ordinary kind, not the kind that has been shown to kill mutants.

The deployment target is a single-node k3s on a QNAP, reached over Tailscale. There is no cert-manager and no ingress TLS. The convention established for the HermesMQ console is a hosts-file hostname routed by Traefik on its NodePort.

## Goals / Non-Goals

**Goals:**
- Make the three failure modes found by hand visible without one: deliveries that succeed while being recorded as failures, alerts published where nothing consumes them, and watches whose matches are all suppressed.
- Answer "is this actually cheap?" directly, rather than only through an alert firing or not firing.
- Make watch tuning a reading task instead of a log-grepping one.
- Keep the derived numbers computed in exactly one place, so the UI and the alerts can never disagree about what "below usual" means.

**Non-Goals:**
- No writes. Not editing watches, not acknowledging alerts, not triggering runs. A write path means auth, CSRF, and an audit story, and none of that is worth it to save an `INSERT`.
- No authentication. Tailnet-only is the boundary; inventing a login for a household tool is more surface than protection.
- No public exposure, ever. This is a record of what a household buys.
- No second implementation of the statistics. If the UI needs a number, the API computes it with `pricehistory`.
- No replacement for the Home Assistant alerts. This is for looking back, not for being told.

## Decisions

**A separate service reading the same database, rather than an API inside `demeter-service`.**
The alternative folds an HTTP server into the batch job, which means the daily run and the UI share a JVM, a failure domain, and a deploy. A UI request pattern could then affect a run against a rate-limited upstream. Separation also allows a `SELECT`-only role, so the read path is structurally incapable of corrupting the history — which matters more than usual here, because flyers expire and the history cannot be re-fetched.

The cost is a second consumer of a schema that was previously private to one service. Accepted: the schema is already the contract between demeter's own modules, and phase 1 exists partly to find out how stable it is in practice.

**The API computes; the UI displays.**
`PriceStats` and `DealVerdict` are Scala and mutation-tested. Reimplementing weighted medians in TypeScript would put the number a chart draws and the number an alert quotes in two places, free to drift. The API therefore returns computed series and verdicts, not raw rows for the client to aggregate.

**Phase 1 ships without a frontend.**
The last-run report as JSON is an afternoon's work and immediately removes the `kubectl logs` habit. It also answers the open question — whether a separate reader over this schema is comfortable — before any frontend is committed to.

**Read-only at the database, not just by convention.**
A dedicated role with `GRANT SELECT` and nothing else. Convention is not a control: the whole point is that this service cannot damage the one dataset that cannot be rebuilt.

## Risks / Trade-offs

- **Schema coupling.** A second consumer makes migrations harder. Mitigated by the API owning its own query layer rather than importing demeter's stores wholesale, so a column rename breaks one file, loudly, at compile time.
- **The history is the irreplaceable asset.** A read-only role plus no write endpoints is the mitigation; the backup story (`pg_dump`) remains demeter's, not this service's.
- **The statistics this leans on are less proven than the rest.** The argument for reusing `pricehistory` rather than reimplementing it is that one implementation cannot drift from another — which holds regardless. But `pricehistory` sits outside the mutation gate, so "reuse the tested code" overstates it: reuse the *single* implementation, and consider adding it to `scripts/stryker.sh` before a UI starts presenting its output as fact.
- **Confidence is easy to misread visually.** A chart that draws Low-confidence parsed-from-text prices identically to scalar prices will mislead. The series must carry `price_confidence` per point and the UI must render it distinguishably — this is a requirement, not a nicety.
- **Scope creep toward writes.** "Just let me pause a watch from here" is the obvious next request and would drag in the entire auth story. Named as a non-goal so the answer is decided in advance.

## Migration Plan

Additive; nothing to migrate. Phase 1 deploys alongside demeter and can be removed with no trace. The `SELECT`-only role is created by hand and recorded in the chart's documentation.

Rollback is deleting the release.

## Open Questions

- Does the frontend live in this repo or its own? `hermes-ui` chose its own repo with its own chart, which worked well; the counter-argument is that this UI is meaningless without demeter's schema and versioning them together is honest.
- Is one run report enough, or does the UI need run history? The report is currently logged and not persisted — showing trends over time would need a `run_report` table, which is the first thing in this proposal that would modify demeter.
- Should `insight-api` expose Prometheus metrics for demeter's own runs, replacing the log-scraped exposition? Attractive, but it means the metrics endpoint reports on a service other than the one serving it.
