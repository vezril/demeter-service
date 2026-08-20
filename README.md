# demeter-service

A self-hosted service that polls Canadian retail flyer data daily, normalizes it,
builds its own price history, and alerts on genuine deals for a personal watchlist.

Primary source: the undocumented Flipp backend. Enrichment: PC Express, Voila,
Canadian Tire. Baseline history: Project Hammer. Alerts route into Home Assistant.

## Layout

Modules mirror the bounded contexts in `specs/`, in dependency/build order:

| Module | Context | Depends on |
|--------|---------|------------|
| `foundations`   | 00 — domain types, Money, PostalCode, Locale, errors | — |
| `ingestion`     | 01 — FlyerSource + Flipp adapters + HTTP policy | foundations |
| `normalization` | 02 — pure parsers (price/multibuy/unit/bilingual) | foundations |
| `persistence`   | 03 — schema, raw store, observation store, ledger | foundations |
| `watchlist`     | 04 — watch model, normalizer, matcher, scoring | foundations, normalization |
| `alerting`      | 05 — decision, dedup, render, HA + fallback sinks | foundations, watchlist, pricehistory |
| `enrichment`    | 06 — PC Express / Voila / Canadian Tire sources | foundations, ingestion, normalization |
| `pricehistory`  | 07 — Hammer loader, rolling stats, deal verdict | foundations, persistence |
| `orchestration` | 08 — daily run, degradation, observability, config | all |

The `specs/` directory holds the spec-driven contracts (SDD markdown + embedded
Gherkin). Each unit spec maps to a source file; each Gherkin scenario maps to tests.
`fixtures/` holds real captured Flipp responses for the `@boundary`/`@contract` tests.

## Build

```bash
sbt compile        # compiles all modules
sbt test           # runs every module's suites
sbt foundations/test             # one module
scripts/stryker.sh # mutation testing on the @pure modules (NOT `sbt stryker` — see below)
sbt scalafmtAll    # format
```

Requires JDK 17+ and sbt 1.10.x.

Mutation testing goes through `scripts/stryker.sh`, not `sbt stryker` directly.
Stryker4s runs the tests of sbt's *current project*, so a task-scoped invocation
(`sbt foundations/stryker`) runs against the root aggregate — which has no test
framework on its classpath — and reports every mutant as NoCoverage. The script
switches the current project and scopes the mutate globs to one module per run,
since stryker4s reads its config only from the build root.

```bash
scripts/stryker.sh                 # foundations + normalization
scripts/stryker.sh normalization   # just one
```

The persistence suites are `@boundary` tests against a real PostgreSQL. Start it
first, or they cancel themselves (the rest of the suite still runs):

```bash
docker compose up -d postgres
```

## How to build it out

Work in spec order, TDD per unit:
1. Pick the next unit spec (start `specs/00-foundations/00.1-money.spec.md`).
2. Translate its Gherkin scenarios into ScalaTest cases (red).
3. Implement the unit until green.
4. For `@pure` units, run `sbt stryker` and triage survivors to the mutation gate.
5. Human sign-off at each phase gate (after 00–03, after 04–05, after 06–08).

## Running it

Configuration is environment-driven and validated at boot; a bad value stops
startup with a specific message rather than failing three layers deep later.

| Variable | Purpose |
|---|---|
| `DEMETER_POSTAL_CODE` | the postal code to poll (default `H2X1Y6`) |
| `DEMETER_LOCALE` | `en-ca` or `fr-ca` |
| `DEMETER_JDBC_URL` / `DEMETER_DB_USER` / `DEMETER_DB_PASSWORD` | Postgres connection |
| `DEMETER_HA_WEBHOOK` / `DEMETER_HA_MQTT_TOPIC` | Home Assistant alert target |
| `DEMETER_NTFY_URL` | fallback sink |
| `DEMETER_PCEXPRESS_ENABLED` / `DEMETER_PCEXPRESS_KEY` | optional enrichment |

Secrets are read from the environment, never committed, and redacted in the
startup config dump.

## Boundaries

Personal-use, facts-only, no redistribution of flyer content. Undocumented
endpoints are wrapped defensively; `@contract` tests are the early-warning system
for upstream drift. See `specs/README.md` for the full conventions and non-goals.
