# Persistence

Source contracts: `specs/03-persistence/03.1`–`03.4`. Module: `modules/persistence`. PostgreSQL via doobie; SQL kept in one repository module per table. All units `@boundary` (integration-tested against a real/store-backed instance).

## ADDED Requirements

### Requirement: Storage schema invariants
The schema (`raw_response`, `merchant`, `flyer`, `product`, `price_observation`) SHALL make three things cheap: replaying normalization from archived raw responses, building price history keyed on `ProductKey`, and diffing flyers week over week. Invariants: every `price_observation` references the `raw_response` it was derived from (raw before parsed — history is rebuildable from raws alone); nullable prices are first-class (`effective_cents` NULL is valid and queryable, no sentinels); `UNIQUE (product_key, flyer_id, observed_at)` makes re-runs idempotent; no flyer imagery is stored anywhere (image URLs in archived JSON bodies are fine; the images are never fetched or stored). `raw_response.body_sha256` supports dedup of identical fetches. (Ref: `specs/03-persistence/03.1-schema.spec.md`)

#### Scenario: Every observation is traceable to a raw response
- **WHEN** a stored `price_observation`'s `raw_response_id` is looked up
- **THEN** a `raw_response` row exists with the exact originating bytes

#### Scenario: A null effective price is storable and queryable
- **WHEN** an observation with no effective price and basis `PercentOffUnknown` is stored and promos-with-unknown-price are queried
- **THEN** the observation is returned

#### Scenario: History can be rebuilt from raw responses alone
- **WHEN** normalization is replayed over `raw_response` rows into an emptied `price_observation` table
- **THEN** observations are repopulated and match the originals for clean cases

### Requirement: Raw response store
`RawResponseStore` SHALL archive the exact bytes of every upstream fetch before anything parses them: `put` stores bytes verbatim plus metadata and sha256 (with optional, off-by-default dedup returning the existing id for an identical recent body); `get` returns the exact bytes byte-for-byte with no re-encoding; `stream(source, kind)` drives replay/back-fill. (Ref: `specs/03-persistence/03.2-raw-response-store.spec.md`)

#### Scenario: Stored bytes are returned byte-for-byte
- **WHEN** arbitrary bytes are put and then got back
- **THEN** the retrieved bytes are identical to the stored bytes

#### Scenario: The archive is written before parsing is attempted
- **WHEN** a fetch that will fail to decode flows through the pipeline
- **THEN** the raw response is already persisted and the decode failure does not remove it

#### Scenario: Identical fetches can be deduplicated by content hash
- **WHEN** dedup-on-hash is enabled and a body identical to one stored recently (same source, kind, postal, locale) is put
- **THEN** the existing id is returned and no new row is created

#### Scenario: Replay streams every archived response
- **WHEN** 5 archived `flyer_items` responses for source flipp are streamed
- **THEN** all 5 are yielded with their ids

### Requirement: Idempotent observation store
`ObservationStore` SHALL persist observations idempotently — `save` upserts on `(product_key, flyer_id, observed_at)` where an existing triple is a counted no-op, never an error or duplicate; `saveAll` is transactional per flyer and returns a `SaveReport(inserted, skippedDuplicate, failed)` feeding metrics; product rows are upserted from observations. Read paths: `observationsFor(key, since)` for rolling stats and `currentObservations(merchant, activeAt)` for matching what's on sale now. (Ref: `specs/03-persistence/03.3-observation-store.spec.md`)

#### Scenario: Saving the same observation twice inserts once
- **WHEN** the same observation for (key K, flyer F, time T) is saved twice
- **THEN** the report shows 1 inserted, 1 skipped duplicate, and exactly one matching row exists

#### Scenario: A batch save reports per-item outcomes
- **WHEN** a batch of 10 observations where 2 duplicate existing rows is saved
- **THEN** the report shows 8 inserted and 2 skipped

#### Scenario: Saving upserts the product dimension
- **WHEN** an observation with a not-yet-seen product key is saved
- **THEN** a product row is created with display names and size

#### Scenario: Reads respect window and activity
- **WHEN** history for key K is queried since a cutoff, or current observations are streamed for a merchant at time now
- **THEN** only observations within the window / whose validity window contains now are returned

### Requirement: Flyer dedup ledger
`FlyerLedger.selectToFetch(listing, now)` SHALL select a flyer for full item fetch iff its id was never marked fetched, OR its validity window differs from the recorded one (a re-issued flyer), OR the recorded fetch exceeds a configured max age (default: refetch once per validity window). A flyer already fetched for its current window is skipped, but `first_seen_at`/`last_seen_at` update regardless so a flyer's lifespan stays visible. This is what turns ~120 daily heavy fetches into ~15 a week. (Ref: `specs/03-persistence/03.4-flyer-dedup-ledger.spec.md`)

#### Scenario: A never-seen flyer is selected
- **WHEN** the listing contains flyer 900 which the ledger has never recorded
- **THEN** flyer 900 is selected for fetching

#### Scenario: An already-fetched flyer for its current window is skipped
- **WHEN** flyer 900 was fetched for window [Jul23, Jul30] and today's listing shows the same window
- **THEN** flyer 900 is not selected

#### Scenario: A re-issued flyer with a changed window is re-selected
- **WHEN** flyer 900 was fetched for [Jul16, Jul23] and now lists with [Jul23, Jul30]
- **THEN** flyer 900 is selected

#### Scenario: Seen timestamps update even when skipped
- **WHEN** flyer 900 is skipped for re-fetching during selection
- **THEN** its `last_seen_at` is advanced to now

#### Scenario: A stale fetch beyond max age is refreshed
- **WHEN** flyer 900 was last fetched 8 days ago with max age 7 days
- **THEN** it is selected; at 2 days it is not
