## Why

The repo is a scaffold: nine sbt modules with placeholder packages, a full set of SDD contracts in `specs/` (38 unit specs across contexts 00–08 with embedded Gherkin), and captured Flipp fixtures — but no production code. To start building we need the SDD contracts lifted into OpenSpec capabilities so implementation can proceed spec-first, phase-gated, and in dependency order.

## What Changes

- Implement the full demeter-service daily pipeline: poll Canadian retail flyer data (Flipp backend), normalize it, build price history, and alert on genuine deals for a personal watchlist via Home Assistant.
- Build the nine bounded contexts in dependency order, TDD per unit, translating each spec's Gherkin scenarios into ScalaTest cases:
  - **Phase 1 (00–03)**: foundations (Money, PostalCode, Locale/BilingualText, domain model, error taxonomy), Flipp ingestion (FlyerSource seam + 3 endpoint adapters + decoders + HTTP policy), pure normalization parsers, PostgreSQL persistence (raw archive, observations, dedup ledger).
  - **Phase 2 (04–05)**: watchlist model/matcher/scoring, alert decision/dedup/render/delivery (HA + fallback sinks).
  - **Phase 3 (06–08)**: retailer enrichment sources (PC Express, Voilà, Canadian Tire), price history (Project Hammer loader, rolling stats, deal verdict), daily-run orchestration with degradation, observability, and config.
- Mutation testing (`sbt stryker`) gates the `@pure` modules; `@contract` tests against `fixtures/` are the early-warning system for upstream drift.
- Human sign-off at each phase gate (after 00–03, after 04–05, after 06–08).

## Capabilities

### New Capabilities

- `foundations`: Core value types and taxonomy every context shares — exact-cents Money with half-even division, Canadian PostalCode validation/canonicalization, Locale + BilingualText container, the immutable domain model (Flyer, FlyerItem, PriceObservation, PriceBasis, Confidence), and the DealWatchError hierarchy with pinned retriability.
- `flyer-ingestion`: The swappable FlyerSource seam and the Flipp backend adapters (flyers listing, per-flyer items, item search), lenient-but-disciplined response decoders, and the shared polite HTTP client policy (retry/backoff/jitter, rate limiting, bot-wall detection).
- `normalization`: Pure parsers turning messy flyer text into storable observations — price-text tokens, multi-buy, percentage-off, unit-price/size extraction, bilingual name splitting, the precedence-ladder observation assembler, and the stable merchant-scoped ProductKey.
- `persistence`: PostgreSQL schema and stores — raw-response archive (replayable byte-for-byte), idempotent observation store with product upsert, and the flyer dedup ledger that selects only new/changed flyers for full fetch.
- `watchlist`: The WatchItem model and validation, shared accent-folding text normalizer, layered matcher (merchant scope → token containment → fuzzy fallback) over bilingual forms, and match scoring/ranking.
- `alerting`: The pure deal decision (maxPrice/requireSale/minDiscountPct + history verdict), alert dedup keyed on watch+product+flyer window with price-drop re-alerts, locale-aware alert rendering, and delivery via Home Assistant (webhook/MQTT) with a fallback sink chain (ntfy, email).
- `enrichment`: Optional, independently-degradable retailer-direct sources supplying regular/unit prices — the EnrichmentSource interface plus PC Express, Voilà (IGA), and Canadian Tire adapters.
- `price-history`: The quantitative heart of "is this cheap?" — Project Hammer baseline loader, confidence/provenance-weighted rolling price stats, and the DealVerdict scorer (BestEver/BelowUsual/Notable/AtOrAboveUsual/Unknown).
- `orchestration`: The idempotent daily run tying everything together, source-degradation policy (BotWall → fallback + operator alert), observability (run report, drift alarms, metrics, optional status endpoint), and validated fail-fast configuration.

### Modified Capabilities

None — greenfield; `openspec/specs/` is empty.

## Impact

- **Code**: all nine sbt modules under `modules/` gain real sources and tests; each `ScaffoldSpec.scala` is deleted as real tests replace it.
- **Dependencies**: already declared in `build.sbt` — cats/cats-effect, http4s + circe (ingestion, alerting, enrichment), doobie (persistence, pricehistory), pureconfig/log4cats/logback (orchestration); ScalaTest + ScalaCheck + Stryker4s for tests.
- **External systems**: undocumented Flipp backend (verified live 2026-07-26), PC Express / Voilà / Canadian Tire backends (shapes unverified — `@contract` tests confirm at build time), Project Hammer dataset, PostgreSQL, and the operator's Home Assistant instance.
- **Boundaries**: personal-use, facts-only, no flyer imagery stored or redistributed; polite low-volume fetching; secrets from config, never hardcoded.
