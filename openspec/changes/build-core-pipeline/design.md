## Context

The repo already fixes most architectural decisions: nine sbt modules mirroring the nine SDD contexts in `specs/` (Scala 2.13 with `-Xsource:3`, cats/cats-effect, http4s + circe at the boundaries, doobie for Postgres, pureconfig for config), captured Flipp fixtures in `fixtures/`, and Stryker4s wired for mutation testing. The `specs/` directory holds 38 unit specs with embedded Gherkin; each unit spec maps to one source file and each Gherkin scenario maps to tests. The OpenSpec capability specs in this change distill those contracts — the SDD files remain the fine-grained source of truth during implementation.

Primary data source is the undocumented Flipp backend (verified live 2026-07-26, no auth). Enrichment endpoints (PC Express, Voilà, Canadian Tire) are unverified shapes from public reverse-engineering. Baseline history comes from the Project Hammer dataset. Alerts land in the operator's self-hosted Home Assistant.

## Goals / Non-Goals

**Goals:**
- A daily, idempotent, crash-safe pipeline: poll → diff → fetch → normalize → persist → match → score → decide → dedup → deliver.
- Survivability against upstream breakage: swappable `FlyerSource` seam, `BotWall` as a first-class signal, drop-and-count decoding, raw-bytes-first archival so history is rebuildable without re-fetching.
- Honest data modeling: absence of a price is a representable, storable, alertable state; every derived price carries provenance (`PriceBasis`) and `Confidence` that weight downstream statistics.
- Test rigor proportional to risk: `@pure` units get property tests and a mutation gate (the observation assembler's precedence ladder especially); `@boundary`/`@contract` tests run against captured fixtures, never live endpoints.

**Non-Goals:**
- No flyer imagery storage or redistribution; facts only, personal use (legal boundary from the README).
- No logged-in or account-scoped retailer data; anonymous/static-key endpoints only.
- No cross-merchant or cross-language product identity in the `ProductKey` (price-history handles those fuzzily and additively).
- No multi-postal-code support in v1 (config is shaped so it's an additive change).
- No UI; Home Assistant automations own presentation.

## Decisions

- **Build strictly in module dependency order, phase-gated** — foundations → ingestion/normalization/persistence → watchlist/alerting → enrichment/pricehistory/orchestration, with human sign-off after 00–03, 04–05, and 06–08. Each unit is TDD: translate the spec's Gherkin to ScalaTest (red), implement (green), then mutation-test pure units. Alternative — vertical slice end-to-end first — was rejected because the module graph is a DAG whose lower layers (Money, errors, decoders) are consumed by everything above; churn there multiplies.
- **Errors as values throughout** (`Either[DealWatchError, A]` inside `F`): the orchestrator pattern-matches error kinds to drive retry/degrade policy. Exceptions never cross module boundaries.
- **Raw-before-parsed archival**: adapters return bytes + parse together; the pipeline persists the bytes before trusting the parse. This is what makes decoder bugs recoverable by replay instead of re-fetch.
- **Purity split**: parsing/normalization/matching/scoring/decision logic is pure and effect-free; IO lives only in adapters, stores, sinks, and the orchestrator. This is both the testing strategy (mutation testing needs pure targets) and the reason a fixture-backed `FlyerSource` can drive full-pipeline tests.
- **Precedence ladder as ordered strategies**: the observation assembler's scalar > multibuy > percent-with-base > percent-no-base > text-scraped > unknown policy is represented as an ordered list of attempted strategies, so re-ordering is a one-line, obviously-tested change.
- **Merchant-scoped, version-stamped ProductKey** (`v1:` prefix): accepts marginal over/under-splitting because history is statistical; the stamp allows deliberate migration when normalization improves.
- **PostgreSQL over SQLite**: concurrent writes during the bounded-concurrency fan-out plus real time-series queries; idempotency via `UNIQUE (product_key, flyer_id, observed_at)` upserts.
- **Politeness centralized in one HTTP policy**: full-jitter exponential backoff, per-source token buckets, config-listed bot-wall signatures. Adapters cannot drift into impolite behavior individually.
- **Contract tests as the drift alarm**: `@contract` scenarios pin captured fixtures; when Flipp renames a field they go red first, complemented at runtime by decode-failure-rate and zero-result drift alarms.

## Risks / Trade-offs

- [Flipp adds auth/bot-wall — the most likely fatal event] → `BotWall` is a dedicated error case; degradation policy switches to a configured Apify fallback source or completes a clean partial run, always with an operator alert. The `FlyerSource` seam keeps the rest of the system untouched.
- [Enrichment endpoint shapes are unverified] → each is optional, independently degradable, and gated by `@contract` tests that confirm/deny the shape at build time before anything depends on it; a broken enrichment source only widens verdict uncertainty.
- [Schema drift corrupts silently rather than loudly] → lenient-but-disciplined decoders (unknown fields ignored, field-level `Decode` errors with JSON pointers), drop-and-count per item, and threshold alarms on decode-failure rate and zero-results.
- [ProductKey mis-joins pollute history] → keys are merchant-scoped and conservative; history math is weighted and statistical so noise degrades medians gracefully; version stamp permits re-keying.
- [Half-even rounding or precedence mutations silently corrupt every downstream stat] → Stryker4s mutation gate focused on `Money`, the multibuy division, and the assembler ladder; pinned numeric examples (500/3 → 167) in specs.
- [Alert fatigue kills the product] → `requireSale`/verdict gating (false-sale detector), dedup keyed on watch+product+window with price-drop re-alerts only, and best-match ranking per group.

## Migration Plan

Greenfield — no rollback concerns. Deployment order mirrors the phases; the service is inert until config (postal code, sinks, Postgres) is supplied, and fails fast on invalid config. Hammer baseline loading is a one-off batch step after persistence lands. Delete each module's `ScaffoldSpec.scala` as real tests replace it.

## Open Questions

- Effect stack detail: cats-effect `IO` end-to-end is assumed; confirm fs2 stream boundaries for `RawResponseStore.stream` / `currentObservations` match the doobie streaming API comfortably on Scala 2.13.
- Apify fallback source: config slot exists from day one, but is implementing the actual Apify adapter in scope for this change or deferred until Flipp actually breaks? (Current plan: seam + config now, adapter deferred.)
- Where dedup/alert-history state lives (05.2 consumes it, 05.4 records it): a small `alert_ledger` table alongside the 03.1 schema is the working assumption — confirm during Phase 2.
- Fixtures for enrichment `@contract` tests must be captured live by the operator before 06 lands (keys/session required); until then those tests are pending-tagged.
