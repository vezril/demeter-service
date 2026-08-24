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
| `DEMETER_MQTT_BROKER` | MQTT broker, e.g. `tcp://homeassistant.local:1883` |
| `DEMETER_MQTT_USER` / `DEMETER_MQTT_PASSWORD` | broker credentials, if required |
| `DEMETER_SCHEDULE_CRON` | when to run, default `0 6 * * *` (06:00 daily) |
| `DEMETER_SCHEDULE_ZONE` | schedule timezone, default `America/Montreal` |
| `DEMETER_SCHEDULE_RUN_ON_START` | `true` to run once immediately at boot |
| `DEMETER_PCEXPRESS_ENABLED` / `DEMETER_PCEXPRESS_KEY` | optional enrichment |

Secrets are read from the environment, never committed, and redacted in the
startup config dump.

Alerts reach Home Assistant by webhook or MQTT. Setting `DEMETER_HA_MQTT_TOPIC`
also requires `DEMETER_MQTT_BROKER`, or startup fails — a topic with nowhere to
publish it looks configured and delivers nothing. Messages go out at QoS 1 and
are deliberately **not** retained: a retained alert is redelivered to every new
subscriber, so an HA restart would re-fire the automation about a deal from last
week. No broker connection is opened for a webhook-only deployment.

### Scheduling

`schedule.cron` supports a time of day, optionally restricted to weekdays:
`minute hour * * day-of-week`. Day-of-month and month must be `*`; anything else
is refused at boot rather than silently approximated, because supporting a
fraction of cron while accepting all of its syntax is worse than not accepting
cron at all.

```
0 6 * * *     every day at 06:00
30 18 * * 4   Thursdays at 18:30 — when flyers tend to drop
0 7 * * 1-5   weekdays at 07:00
```

The time is wall-clock in `schedule.zone`, and the next firing is recomputed
from the clock each cycle, so a slow run never drags the schedule later and a
restart lands back on the same slot. DST is handled: a firing inside a
spring-forward gap still happens, and a repeated fall-back hour does not fire
twice.

### The watchlist

Watches live in the `watch_item` table (created by the startup migration), not
in config — so you can add or pause one without redeploying. An empty watchlist
is legal but useless: the run still fetches, normalizes, and stores prices, it
just alerts on nothing, and says so loudly at boot.

```sql
-- alert on milk under $3 that history agrees is genuinely a sale.
-- require_sale defaults to true; set it false to alert on every match, which
-- on a real flyer week means hundreds of alerts per watch.
INSERT INTO watch_item (id, label, terms, max_price_cents)
VALUES ('milk-4l', 'Milk 4L', ARRAY['milk', 'lait'], 300);

-- scoped to specific merchants, and only when it is 20%+ off
INSERT INTO watch_item (id, label, terms, merchant_ids, min_discount_pct)
VALUES ('coffee', 'Coffee', ARRAY['cafe', 'coffee'], ARRAY[2269, 4592], 20);

-- pause one while you tune it, without losing its settings
UPDATE watch_item SET active = false WHERE id = 'coffee';
```

```sql
-- exclusion terms veto a match, whatever it cost: "butter" otherwise catches
-- peanut butter, butter croissants, Butter Chicken and lip butter, and no price
-- ceiling separates them because peanut butter is cheaper than butter
UPDATE watch_item
   SET exclude_terms = ARRAY['arachide','peanut','almond','cookie','croissant','chicken']
 WHERE id = 'butter';
```

`terms` are matched in either language after accent-folding (04.2/04.3), so
`cafe` finds `café`. `exclude_terms` use the same rules, so `arachide` also
catches `arachides`. A term that is also excluded is refused at startup, since
such a watch could never match anything. An empty `merchant_ids` means any merchant. The table's
CHECK constraints mirror the domain rules, so a bad INSERT is rejected outright
rather than surfacing later; anything the database permits but the domain still
refuses is named individually in the startup log and skipped.

## Boundaries

Personal-use, facts-only, no redistribution of flyer content. Undocumented
endpoints are wrapped defensively; `@contract` tests are the early-warning system
for upstream drift. See `specs/README.md` for the full conventions and non-goals.

## Deploy

The service takes its whole configuration from environment variables and binds
no socket: nothing listens, the run report goes to the log, and alerts are
pushed outward to Home Assistant. So there is no Service, no Ingress and no
health endpoint in the chart, and `kubectl logs` is the interface.

```bash
helm upgrade --install demeter charts/demeter-service \
  -n demeter --create-namespace \
  -f charts/demeter-service/values-nas.example.yaml   # edit this first
```

`charts/demeter-service/` runs the service as a single-replica Deployment with
`Recreate`, plus a Postgres StatefulSet on one PVC. Both are deliberate:

- **One replica, never two.** The scheduler runs in-process, so a second replica
  would wake on the same cron and race the first through the same flyers. The
  fetch ledger keeps the *data* honest, but the duplicate requests are aimed at
  an undocumented, rate-limited, bot-walled upstream.
- **Not a CronJob.** The cron lives inside the service, which is what makes the
  run resumable and the degradation policy (08.2) meaningful.
- **No liveness probe.** With nothing bound to a port, an HTTP probe would mean
  inventing an endpoint that proves the JVM answers HTTP rather than that the
  daily run works. The container exits non-zero on unrecoverable config or store
  failure and `restartPolicy` handles that honestly.

Point it at an existing database instead with `postgresql.enabled=false` and the
`externalDatabase.*` values.

The **watchlist lives in the database**, not in the chart values. An empty one
matches nothing, so add rows to `watch_item` before expecting alerts.

The bundled Postgres has one PVC and no backup of its own. Price history is the
part of this system that cannot be re-fetched, because flyers expire:

```bash
kubectl -n demeter exec sts/demeter-demeter-service-postgresql -- \
  pg_dump -U demeter demeter | gzip > demeter-$(date +%F).sql.gz
```

### Images

`docker build .` produces the image; the build stage is pinned to
`$BUILDPLATFORM` so the Scala compile runs once natively and only the JRE base
varies per architecture. Pushing a `vX.Y.Z` tag publishes a multi-arch
(amd64 + arm64) image to `ghcr.io/vezril/demeter-service` — that is the only
thing that publishes, and it never happens as a side effect of merging.

