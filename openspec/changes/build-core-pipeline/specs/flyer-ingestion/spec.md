# Flyer Ingestion

Source contracts: `specs/01-ingestion/01.1`–`01.6`. Module: `modules/ingestion`. Fixtures for `@boundary`/`@contract` tests live in `fixtures/`. Endpoints verified live 2026-07-26; no auth key required.

## ADDED Requirements

### Requirement: FlyerSource interface
Every flyer provider (Flipp today, an Apify fallback, a fixtures source in tests) SHALL implement one `FlyerSource[F]` interface with `flyers(postal, locale)`, `items(flyerId, postal, locale)`, and `search(term, postal, locale)`, each returning `F[Either[DealWatchError, Raw*]]` — failure is a value, never a thrown exception. Results SHALL carry the archived raw bytes alongside the parsed shape (`RawResponse` + parsed lists) so persistence can archive before anything trusts the parse. Sources SHALL advertise a `capabilities: Set[Capability]`; calling an unsupported capability SHALL fail cleanly without any network call. (Ref: `specs/01-ingestion/01.1-flyer-source-interface.spec.md`)

#### Scenario: A source hands back raw bytes alongside parsed flyers
- **WHEN** flyers are requested from a source backed by a captured response
- **THEN** the parsed flyers, the original raw bytes, and the URL they came from are all returned

#### Scenario: Failures surface as typed values
- **WHEN** the transport returns a 503
- **THEN** the result is `Left(HttpStatus(503))` and no exception escapes

#### Scenario: Calling an unsupported capability fails cleanly
- **WHEN** `search` is called on a source whose capabilities exclude search
- **THEN** it returns a `Left` indicating the capability is unsupported and performs no network call

### Requirement: Flipp flyers endpoint adapter
The adapter SHALL build `GET https://backflipp.wishabi.com/flipp/flyers?locale={queryValue}&postal_code={canonical}` from typed `PostalCode` and `Locale` inputs, archive raw bytes on 2xx, decode via the response decoders, and map non-2xx/timeout/transport to the corresponding error — a 403 or Cloudflare challenge body maps to `BotWall`, not a generic `HttpStatus`. The adapter SHALL do no merchant filtering; selecting what to fetch is the orchestrator's job. (Ref: `specs/01-ingestion/01.2-flipp-flyers-endpoint.spec.md`)

#### Scenario: A live-shaped flyers response decodes (@contract)
- **WHEN** the captured `fixtures/flyers.sample.json` is parsed
- **THEN** every flyer has an id, merchant id, name, and valid window, and each flyer's postal code matches the requested one

#### Scenario: The request URL is built from typed inputs
- **WHEN** the adapter builds a request for postal `"H2X 1Y6"` and locale `EnCa`
- **THEN** the URL is `https://backflipp.wishabi.com/flipp/flyers?locale=en-ca&postal_code=H2X1Y6`

#### Scenario: A Cloudflare challenge maps to BotWall
- **WHEN** the endpoint returns HTTP 403 with a challenge marker in the body
- **THEN** the result is `Left(BotWall)`, non-retriable, flagged for operator attention

#### Scenario: The raw response is preserved on success
- **WHEN** a flyers fetch succeeds
- **THEN** the `RawFlyerListing` carries the exact bytes received and the request URL

### Requirement: Flipp item search adapter
The adapter for `GET /flipp/items/search?locale=&postal_code=&q=` SHALL keep the response's two item arrays separate — `items` (paper-flyer deals) as `flyerItems` and `ecom_items` (e-commerce listings) as `ecomItems` — never conflating them. It SHALL percent-encode the search term, prefer `items[].merchant_name` (populated in search responses) for merchant resolution, capture `normalized_query` for diagnostics, and treat an empty result set as success, not error. (Ref: `specs/01-ingestion/01.3-flipp-item-search-endpoint.spec.md`)

#### Scenario: The two item arrays are decoded separately (@contract)
- **WHEN** the captured `fixtures/items_search.sample.json` is parsed
- **THEN** flyer items and ecom items are returned as separate collections with no ecom item among the flyer items

#### Scenario: The search term is percent-encoded
- **WHEN** the term is `"ground beef"` or `"café"`
- **THEN** the `q` parameter is `ground%20beef` / `caf%C3%A9`

#### Scenario: An empty result set is a success
- **WHEN** a search returns no matches
- **THEN** the result is a `Right` with zero flyer items and the normalized query preserved

### Requirement: Flipp per-flyer items adapter
The adapter for `GET /flipp/flyers/{flyer_id}?locale=&postal_code=` SHALL be a faithful, tolerant transcription of the JSON into `FlyerItem`s: a null `current_price` is never a decode failure (it yields `currentPrice = None` — deriving a price is normalization's job); null `merchant_name` is expected (identity comes from the flyerId→merchantId mapping); malformed individual items are dropped and counted, never fatal; and the `pages` flyer imagery is never retained (legal boundary). (Ref: `specs/01-ingestion/01.4-flipp-flyer-items-endpoint.spec.md`)

#### Scenario: Null current_price yields a price-absent item, not a failure
- **WHEN** an item with `current_price: null` and `sale_story: "50% off"` is parsed
- **THEN** a `FlyerItem` with no current price is produced, the sale story preserved, no error raised

#### Scenario: Malformed individual items are dropped and counted
- **WHEN** a flyer response of 10 items includes 2 lacking a name
- **THEN** 8 valid items are returned, 2 dropped items are reported, and the overall result is a `Right`

#### Scenario: A bilingual name is preserved raw for later splitting
- **WHEN** an item named `"LAIT FINEMENT FILTRÉ NATREL | NATREL FINE-FILTERED MILK"` is parsed
- **THEN** `rawName` equals that exact string and the bilingual split is deferred to normalization

#### Scenario: Flyer image pages are not retained
- **WHEN** a flyer response containing a `pages` array is parsed
- **THEN** no page imagery is included in the returned items

### Requirement: Flipp response decoders
Pure decoders SHALL turn Flipp JSON into domain objects with disciplined leniency: unknown fields ignored (never fatal); a price field decodes number, numeric-string, or null (null/absent → `None`; non-numeric non-null string → field-level `Decode` error); timestamps parse ISO-8601-with-offset to `Instant`; an item with `validTo <= validFrom` is a `Decode` rejection. Decode errors SHALL carry a JSON pointer to the offending path (e.g. `items[3].current_price`). (Ref: `specs/01-ingestion/01.5-flipp-response-decoders.spec.md`)

#### Scenario: Price fields decode across Flipp's real shapes
- **WHEN** the JSON value is `4.99`, `"4.99"`, `10`, `null`, absent, or `0`
- **THEN** the result is `Some(499)`, `Some(499)`, `Some(1000)`, `None`, `None`, `Some(0)` respectively

#### Scenario: A non-numeric non-null price is a field-level decode error
- **WHEN** the JSON value is `"N/A"`, `"see store"`, or `"$4.99"`
- **THEN** decoding fails with a `Decode` error naming the field

#### Scenario: An item with an inverted validity window is rejected
- **WHEN** an item has `valid_from` after `valid_to`
- **THEN** it fails with a `Decode` error

#### Scenario: Unknown extra fields never break decoding
- **WHEN** an item JSON contains fields the decoder has never seen (property test with random extra keys)
- **THEN** it decodes successfully, ignoring them

### Requirement: HTTP client policy
One shared policy (used by all ingestion and enrichment adapters) SHALL own: per-request timeouts (default 30s → `Timeout`, retriable); retry of retriable errors with exponential backoff and full jitter (`wait = min(cap, base·2^(n−1)) · random[0,1]`, base 1s, cap 30s, max 3 attempts) and no retry of non-retriable errors; a per-source token-bucket rate limiter; a realistic stable User-Agent with Accept-Language matching the requested locale; and bot-wall detection (403, challenge-marked 429, or body matching configurable signatures → `BotWall`, short-circuiting retry). All calls are GETs so retries are always safe. (Ref: `specs/01-ingestion/01.6-http-client-policy.spec.md`)

#### Scenario: A retriable error is retried up to the attempt cap then surfaced
- **WHEN** a request through the policy hits a transport failing 503 every attempt with max 3 attempts
- **THEN** exactly 3 attempts are made and the final result is `Left(HttpStatus(503))`

#### Scenario: A non-retriable error is not retried
- **WHEN** the transport returns 404
- **THEN** exactly 1 attempt is made

#### Scenario: Backoff wait stays within the jittered bound (property)
- **WHEN** the wait for attempt n is computed with base 1s, cap 30s
- **THEN** the wait is between 0 and `min(30s, 1s·2^(n−1))`

#### Scenario: A Cloudflare challenge short-circuits to BotWall without retry
- **WHEN** the transport returns 403 with body marker `"cf-chl-bypass"`
- **THEN** the result is `Left(BotWall)` after exactly 1 attempt

#### Scenario: The rate limiter serializes bursts within a source
- **WHEN** 5 requests are issued in a tight loop through a 2-per-second limiter
- **THEN** no more than 2 requests start within any 1-second window
