## 1. Foundations (specs/00-foundations, modules/foundations)

- [x] 1.1 Money: TDD from 00.1 Gherkin — smart constructors returning Either[MoneyError, Money], exact cents, half-even division, parse/format round-trip (property test)
- [x] 1.2 PostalCode: TDD from 00.2 — shape + illegal-letter validation (named constant), canonical/withSpace forms, FSA accessor
- [x] 1.3 Locale + BilingualText: TDD from 00.3 — queryValue rendering; primary/anyForm/forms accessors with dedup
- [x] 1.4 Core domain model: TDD from 00.4 — Flyer/FlyerItem/PriceObservation, PriceBasis, Confidence; window validation; rawName immutability
- [x] 1.5 DealWatchError: TDD from 00.5 — sealed hierarchy, pinned retriability table, context maps
- [x] 1.6 Foundations ScaffoldSpec deleted; `foundations/test` green (29/29); mutation gate PASSES at 97.14% (68/70 killed). The 2 survivors are genuinely equivalent: the currency-mismatch guard is unkillable while Currency has only CAD, and Confidence.min returns the same case object either way.

## 2. Ingestion (specs/01-ingestion, modules/ingestion)

- [x] 2.1 FlyerSource interface + Raw* types + capabilities set; fixture-backed test source (01.1)
- [x] 2.2 Response decoders (01.5): priceField (number|string|null), instantField, flyer/item/envelope decoders with JSON-pointer Decode errors; @contract tests against fixtures/; property test for unknown-field tolerance
- [x] 2.3 HTTP client policy (01.6): pure backoff math (full jitter, property-tested), retry/no-retry per error kind, token-bucket rate limiter, headers, config-listed bot-wall signature detection
- [x] 2.4 Flipp flyers adapter (01.2): typed URL building, raw+parsed return, BotWall mapping; @contract test on fixtures/flyers.sample.json
- [x] 2.5 Flipp per-flyer items adapter (01.4): null-price tolerance, drop-and-count, no pages imagery retained
- [x] 2.6 Flipp item search adapter (01.3): items vs ecom_items kept separate, percent-encoded terms, empty-result-is-success; @contract test on fixtures/items_search.sample.json
- [x] 2.7 Delete ingestion ScaffoldSpec; module green

## 3. Normalization (specs/02-normalization, modules/normalization)

- [x] 3.1 Price text parser (02.1): currency symbols both sides, comma-decimal, thousands separators, cents notation, unit-suffix capture
- [x] 3.2 Multi-buy parser (02.2): N-for-$X with half-even unit price (pin 500/3→167), BOGO with/without base, savings-tail immunity
- [x] 3.3 Percentage-off parser (02.3): en/fr forms, upper-bound ranges, half-price, bare free vs BOGO, loyalty-points fall-through
- [x] 3.4 Unit price calculator (02.4): size extraction incl. multipacks and fr/en pack words, standard-unit normalization, unit price with half-even
- [x] 3.5 Bilingual name splitter (02.5): separators, diacritic+stopword language detection, ambiguous→both-forms-Low, three-segment edge case
- [x] 3.6 Product key (02.7): merchant-scoped, normalized-token + size hash, v1: version stamp, determinism property test (needs 4.2's normalizer — implement watchlist text normalizer first if sequencing strictly, or share from a common location per design)
- [x] 3.7 Observation assembler (02.6): strategy-list precedence ladder, confidence-minimum rule, size/unit-price attachment, saleText preservation
- [x] 3.8 Normalization ScaffoldSpec deleted; `normalization/test` green (68/68); mutation gate PASSES at 87.43% (160/183 killed), up from 61.2%. Above the 85% "low" mark. The 23 remaining survivors are concentrated in BilingualSplitter's language-scoring heuristic, which the spec explicitly calls a cheap approximation rather than a contract.

## 4. Persistence (specs/03-persistence, modules/persistence)

- [x] 4.1 Schema DDL + migrations for raw_response, merchant, flyer, product, price_observation with the 03.1 uniqueness and FK invariants; docker-compose Postgres for integration tests
- [x] 4.2 RawResponseStore (03.2): byte-for-byte put/get, sha256, optional dedup-on-hash, fs2 replay stream
- [x] 4.3 ObservationStore (03.3): upsert-on-triple idempotency, transactional saveAll with SaveReport, product upsert, observationsFor/currentObservations queries
- [x] 4.4 FlyerLedger (03.4): selectToFetch rule (never-seen | changed-window | stale), markFetched, seen-timestamp updates on skip
- [x] 4.5 Replay/rebuild integration test: raws → replay normalization → observations repopulated (03.1 rebuild scenario)
- [x] 4.6 Delete persistence ScaffoldSpec; module green

## 5. Phase gate 1 (after 00–03)

- [x] 5.1 PHASE GATE 1 SIGNED OFF (2026-08-20). `sbt test` green across foundations/ingestion/normalization/persistence; persistence verified against real Postgres (20/20, nothing cancelled); mutation gate green on both @pure modules (97.14% / 87.43%).

## 6. Watchlist (specs/04-watchlist, modules/watchlist)

- [x] 6.1 Matching text normalizer (04.2): NFKD accent-fold, casing, punctuation, whitespace, bilingual stopwords; idempotency property; wire 02.7 to reuse it
- [x] 6.2 WatchItem model + validation (04.1): NonEmptyList terms, empty-set-means-any merchant scope, active flag
- [x] 6.3 Matcher (04.3): scope short-circuit, token containment over bilingual forms, fuzzy fallback with thresholds tuned against the 04.3 examples (yogourt/milkshake/chicken-broth)
- [x] 6.4 Match scoring (04.4): textScore/confidence/priceRank, configurable combined weighting, 0..1 property
- [x] 6.5 Delete watchlist ScaffoldSpec; stryker on the module

## 7. Alerting (specs/05-alerting, modules/alerting)

- [x] 7.1 Deal decision (05.1): pinned gate order (maxPrice → requireSale → minDiscountPct), no-price-promo rules, Suppress reasons
- [x] 7.2 Alert dedup (05.2): AlertKey(watch, product, window), price-drop re-alert / price-rise silence; alert_ledger persistence for dedup state (design open question — confirm shape)
- [x] 7.3 Alert model + rendering (05.3): plain locale-aware one-liner with verdict phrase, fr-ca formatting, structured JSON render
- [x] 7.4 Home Assistant sink (05.4): webhook + MQTT variants against a fake HA, retry-then-fallback, config-only targets
- [x] 7.5 Fallback sinks (05.5): ChainSink first-success semantics, ntfy + email sinks, total-failure run-health signal
- [x] 7.6 Delete alerting ScaffoldSpec; module green

## 8. Phase gate 2 (after 04–05)

- [x] 8.1 End-to-end test: fixture-backed FlyerSource → normalize → persist → match → decide → fake sink delivers exactly once; human sign-off before Phase 3

## 9. Enrichment (specs/06-enrichment, modules/enrichment)

- [x] 9.1 EnrichmentSource interface + EnrichedPrice (06.1); non-blocking-failure semantics
- [ ] 9.2 PARTIAL. Verified all three endpoints live; all three assumed schemas were falsified (specs/06-enrichment/06.0-endpoint-verification.md). VOILA REBUILT and verified: reads window.__INITIAL_STATE__ from the server-rendered page, @contract test against fixtures/voila_search.sample.html, real regular/sale/unit prices. PC EXPRESS not pursued (Akamai Bot Manager; no api.pcexpress.ca call occurs at all). CANADIAN TIRE not rebuilt (real API found at /api/v1/search/v2/search?q=&store=, but the site runs Akamai — operator decision). PC Express and CT decoders still target schemas that do not exist; neither may be enabled in a real run.
- [x] 9.3 PC Express source (06.2): Site-Banner per merchant, X-Apikey from config only, 401 degrades source with operator signal
- [x] 9.4 Voilà source (06.3): establish-session step, one 401 retry then degrade, online-reference provenance
- [x] 9.5 Canadian Tire source (06.4): serial + stricter rate limit, validity-window cache, enrichment-regular-overrides-flyer-claim
- [x] 9.6 Delete enrichment ScaffoldSpec; module green

## 10. Price history (specs/07-price-history, modules/pricehistory)

- [x] 10.1 Hammer loader (07.1): product+raw join, vendor→merchant mapping, lower-trust flags, source=hammer provenance
- [x] 10.2 Rolling stats (07.2): pinned weighted-median interpolation, confidence/provenance weights, window filter, min≤median≤max property
- [x] 10.3 Deal quality scorer (07.3): verdict ladder with config thresholds, thin-history cap at Notable, enrichment-regular override, honest Unknown
- [x] 10.4 Delete pricehistory ScaffoldSpec; stryker on stats/scorer

## 11. Orchestration (specs/08-orchestration, modules/orchestration)

- [x] 11.1 Config (08.4): pureconfig model, fail-fast validation (postal, enabled-source-needs-key, non-empty sink chain), secret redaction in dumps
- [x] 11.2 Daily run (08.1): pinned sequence as F[RunReport], archive-raw-first, bounded-concurrency fan-out, per-flyer failure isolation, end-to-end idempotency test
- [x] 11.3 Source degradation (08.2): BotWall→fallback-or-partial + operator alert, retry-budget degradation, StoreUnavailable fails loudly
- [x] 11.4 Observability (08.3): RunReport emission, decode-failure and zero-result and alert-volume drift alarms, scrape-friendly metrics, optional read-only /status and /history endpoints
- [x] 11.5 Scheduler wiring + main entry point; delete orchestration ScaffoldSpec

## 12. Phase gate 3 (after 06–08)

- [x] 12.1 PHASE GATE 3 SIGNED OFF (2026-08-20). End-to-end run green against fixture source + in-memory doubles; `sbt test` green repo-wide (285 with Postgres up, 269 with the @boundary suites cancelling cleanly); mutation gate green; README verified.

## 13. Post-gate functionality

- [x] 13.1 Watchlist loading (was: `loadWatchlist` returned Nil, so a real run alerted on nothing). Watches now live in a `watch_item` table with CHECK constraints mirroring 04.1's validation; `WatchStore` in the watchlist module (which gains a dependency on persistence, following the pricehistory precedent, since 03 cannot see WatchItem). Main loads at boot, names any domain-rejected rows, and warns loudly when no watch is active. 33 tests, @boundary against real Postgres.
- [x] 13.2 Alert dedup persistence. Prior alerts now live in an alert_ledger table whose PRIMARY KEY is the dedup key itself (watch + product + flyer window), so "same deal, same window, once" is enforced by the schema. DailyRun rehydrates open windows before matching and writes through on delivery; a ledger write failure is reported but never loses the run, since the alert genuinely went out. Store lives in the alerting module (AlertKey is a 05 type persistence cannot see), DDL in persistence.Schema. Covered by restart-survival tests plus @boundary tests against real Postgres.
- [x] 13.3 Scheduler. The cron string is now honoured rather than parsed and ignored: DailySchedule supports a wall-clock time of day optionally restricted to weekdays, in an explicit timezone, and REFUSES at boot anything it cannot honour (day-of-month, month, step syntax) rather than approximating it. The loop recomputes the next firing from the clock each cycle, so a slow run cannot drag the schedule later and a restart lands back on the same slot. Both DST transitions covered by tests.
- [x] 13.4 MQTT sink. Was stubbed to always fail, so a topic-only configuration could never deliver. Implemented over Paho as a Resource (clean connect/disconnect, MemoryPersistence rather than a spool directory), QoS 1, retain deliberately false so an HA restart cannot re-notify about a stale deal. Broker failures map to retriable Transport errors so the fallback chain (05.5) can take over. A topic without a broker is now a boot error; no connection is opened for a webhook-only deployment.
