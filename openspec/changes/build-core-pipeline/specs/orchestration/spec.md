# Orchestration

Source contracts: `specs/08-orchestration/08.1`–`08.4`. Module: `modules/orchestration` (depends on all others). This is the top of the call graph: the scheduled daily job, the degradation policy, observability, and config.

## ADDED Requirements

### Requirement: Idempotent daily run
The daily run SHALL execute the pinned sequence — listing → ledger diff → per-flyer fetch/normalize/persist (archive-raw-first) under bounded concurrency → match active observations against the watchlist → stats + verdict (+ optional enrichment) → decide → dedup → deliver — as a single `F[RunReport]` that never throws: per-flyer failures are collected into the report, not propagated. Enrichment is behind a flag; when off, verdicts run on history alone. The run is idempotent end to end: running twice in a day produces no duplicate observations and no duplicate alerts. (Ref: `specs/08-orchestration/08.1-daily-run.spec.md`)

#### Scenario: Only ledger-selected flyers are fetched
- **WHEN** the listing has 100 flyers and the ledger selects 12 as new or changed
- **THEN** exactly 12 per-flyer item fetches are made

#### Scenario: One failing flyer does not sink the run
- **WHEN** 1 of 12 flyers returns a decode error
- **THEN** the other 11 are normalized and stored, the report records 1 flyer failure, and the run completes successfully

#### Scenario: The run is idempotent within a day
- **WHEN** a completed run executes again the same day
- **THEN** no duplicate observations are stored and no duplicate alerts are delivered

#### Scenario: A new best-ever deal on a watched item is delivered once
- **WHEN** a new flyer carries a watched item at a best-ever price
- **THEN** exactly one alert for that deal is delivered

#### Scenario: Bounded concurrency is respected
- **WHEN** 12 flyers fan out with a concurrency limit of 3
- **THEN** no more than 3 per-flyer fetches are in flight at once

### Requirement: Source degradation policy
The run SHALL turn error kinds into source-level decisions: Flipp `BotWall` → mark Flipp degraded for the run, attempt the configured fallback `FlyerSource` (Apify) if enabled, always emit an operator-attention alert, never retry a bot wall in a loop; repeated Flipp 5xx/timeouts past the retry budget → degrade and continue with what was fetched; an enrichment source down → drop enrichment for the run (history-only verdicts, alerts still delivered); `StoreUnavailable` past retries → fail the run loudly (a run that can't persist can't be trusted). Degradations are recorded in the `RunReport`. (Ref: `specs/08-orchestration/08.2-source-degradation.spec.md`)

#### Scenario: A Flipp bot wall switches to the fallback and alerts the operator
- **WHEN** Flipp returns `BotWall` and an Apify fallback is configured
- **THEN** the fallback provides the listing, an operator alert is emitted, and Flipp is not retried in a loop

#### Scenario: A bot wall with no fallback yields a clean partial run
- **WHEN** Flipp returns `BotWall` with no fallback configured
- **THEN** the run completes as a partial run with Flipp marked degraded and an operator alert emitted

#### Scenario: An enrichment outage drops to history-only verdicts
- **WHEN** PC Express is down during scoring
- **THEN** verdicts compute from history alone and alerts are still delivered

#### Scenario: A persistent store outage fails the run loudly
- **WHEN** the observation store is unavailable past the retry budget
- **THEN** the run fails with a clear `StoreUnavailable` outcome and does not silently drop observations

### Requirement: Observability and drift detection
The service SHALL emit a per-run `RunReport` (flyers listed/selected/fetched/failed, items parsed/dropped, observations inserted/skipped, matches, alerts delivered/suppressed, degraded sources, wall-clock) and raise drift alarms: decode-failure rate per source above threshold (schema drift); zero-result anomaly when a normally-productive source returns ~0 without a transport error (distinct from a genuinely empty search); alert-volume anomaly on a sudden drop to zero across a normally-active watchlist. Metrics SHALL be scrape-friendly (Prometheus text or structured logs). An optional read-only HTTP `/status` and `/history/{productKey}` serves own-consumption data only — never flyer imagery or bulk flyer content. (Ref: `specs/08-orchestration/08.3-observability.spec.md`)

#### Scenario: A run emits a complete report
- **WHEN** a run completes
- **THEN** the report includes counts for flyers, items, observations, matches, alerts, and any degraded sources

#### Scenario: A decode-failure spike raises a drift alarm
- **WHEN** a source's decode-failure rate exceeds the threshold
- **THEN** a drift alarm names the source and is distinguishable from a transport outage

#### Scenario: A zero-result anomaly is distinct from a genuine empty search
- **WHEN** a historically productive source returns zero flyers with no transport error
- **THEN** a zero-result anomaly is raised, while a genuinely empty item search raises none

#### Scenario: The status endpoint serves own-consumption data only
- **WHEN** the enabled read-only endpoint is queried
- **THEN** it returns run health and price history, never raw flyer imagery or bulk flyer content

### Requirement: Validated fail-fast configuration
All operational knobs SHALL live in one config loaded and fully validated at startup — postal code (v1 single; multi is an additive change), locale, source toggles/URLs/fallback token, enrichment keys, HTTP policy (timeout/attempts/backoff/rate limits/UA), scoring weights and deal thresholds, history window and min-n floors, sink chain and order, schedule, and Postgres connection. Invalid config SHALL stop startup with a specific message; enabling a source without its key is a boot-time error, not a silent runtime degradation; secrets come from environment/secret file, are never committed, and are redacted in any config dump. (Ref: `specs/08-orchestration/08.4-config.spec.md`)

#### Scenario: An invalid postal code stops startup
- **WHEN** the config carries postal code `"12345"`
- **THEN** startup fails with a clear postal-code error

#### Scenario: Enabling a source without its key fails at boot
- **WHEN** PC Express enrichment is enabled with no API key provided
- **THEN** startup fails naming the missing key and the service does not start half-configured

#### Scenario: An empty sink chain is rejected
- **WHEN** the alert sink chain is empty
- **THEN** startup fails (alerts would have nowhere to go)

#### Scenario: A config dump redacts secrets
- **WHEN** the loaded config is logged at startup
- **THEN** secret values are redacted while non-secret settings show for diagnostics
