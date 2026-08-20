# Enrichment

Source contracts: `specs/06-enrichment/06.1`–`06.4`. Module: `modules/enrichment`. Enrichment is best-effort and advisory — a missing enrichment never blocks an observation or alert; it only sharpens the deal verdict. All sources use the shared HTTP policy and error model, anonymous/static-key endpoints only (no logged-in account data). The PC Express, Voilà, and Canadian Tire endpoint shapes come from public reverse-engineering and are NOT verified live — the `@contract` scenarios exist to confirm or deny them at build time.

## ADDED Requirements

### Requirement: Enrichment source interface
Each retailer-direct source SHALL implement `EnrichmentSource[F]` — `name`, `merchantsCovered: Set[MerchantId]`, and `lookup(query, near, locale)` returning `F[Either[DealWatchError, List[EnrichedPrice]]]` where `EnrichedPrice` carries merchant, bilingual name, optional regular/sale prices, optional unit price, source, and fetch time. Each source is optional and independently degradable: a failure (including `BotWall`) degrades that source only. (Ref: `specs/06-enrichment/06.1-enrichment-source-interface.spec.md`)

#### Scenario: A successful lookup returns regular and unit prices
- **WHEN** `"milk 4L"` is looked up near a postal code on a source covering merchant 123
- **THEN** enriched prices with regular price and unit price (where available) are returned

#### Scenario: An enrichment failure is non-blocking
- **WHEN** an enrichment source errors during a run
- **THEN** the run continues and the affected observations simply lack enrichment

### Requirement: PC Express source
The PC Express adapter (Loblaw banners: Maxi, Provigo, No Frills, Superstore, …) SHALL POST to the product-facade search endpoint with the `Site-Banner` header derived from the target merchant and the static `X-Apikey` read from operator-supplied config — never a source-code literal. Products map to `EnrichedPrice` (name per `lang`, regular price, sale price, comparison/unit price, banner → merchant). A 401 (bad/missing key) degrades this source only, with an operator-attention signal naming the credential problem. (Ref: `specs/06-enrichment/06.2-pcexpress-source.spec.md`)

#### Scenario: A product search response maps to enriched prices (@contract)
- **WHEN** a captured PC Express search response for "lait" is parsed
- **THEN** each product yields an `EnrichedPrice` with a regular price where present and the banner mapped to the correct merchant id

#### Scenario: The banner header follows the target merchant
- **WHEN** a lookup targets Maxi or Provigo
- **THEN** the `Site-Banner` header is `maxi` / `provigo`

#### Scenario: A rejected API key degrades this source only
- **WHEN** the endpoint returns 401
- **THEN** only PC Express is degraded and an operator-attention signal names the credential problem

### Requirement: Voilà source
The Voilà (Sobeys/IGA) adapter SHALL search `voila.ca/api/v5/products/search`, first establishing the required session cookie as a discrete step it can re-do: a 401 from an expired session re-establishes the session and retries once; a second 401 degrades the source. Voilà prices are marked as online reference prices (they can differ from in-store IGA flyer prices) and price-history treats them as advisory. (Ref: `specs/06-enrichment/06.3-voila-source.spec.md`)

#### Scenario: A products search response maps to enriched prices (@contract)
- **WHEN** a captured Voilà search response for "lait" is parsed
- **THEN** each product yields an `EnrichedPrice` with prices in exact cents

#### Scenario: A session is established before searching and re-established once on expiry
- **WHEN** a lookup is attempted with no existing session
- **THEN** the adapter establishes a session first, then searches
- **WHEN** a search returns 401 for an expired session
- **THEN** the adapter re-establishes and retries once; a second 401 degrades the source

#### Scenario: Voilà prices carry online-reference provenance
- **WHEN** an `EnrichedPrice` from Voilà is inspected
- **THEN** its provenance marks it as an online reference price, advisory relative to the flyer observation

### Requirement: Canadian Tire source
The Canadian Tire adapter (CanadianTracker's API path: products → SKUs → prices) SHALL be fetched sparingly and cached hard: lookups run serially (never in parallel) under a stricter rate limit than the grocery sources, and enrichment for a product is cached for the flyer's whole validity window. Discount depth for CT deals SHALL use the CT enrichment regular price as baseline, not the flyer's stated (often inflated) regular. (Ref: `specs/06-enrichment/06.4-canadian-tire-source.spec.md`)

#### Scenario: A price response yields a regular price for a SKU (@contract)
- **WHEN** a captured Canadian Tire price response is parsed
- **THEN** it yields an `EnrichedPrice` with a regular price

#### Scenario: CT lookups are serialized and rate-limited more strictly
- **WHEN** several CT lookups are queued
- **THEN** they execute serially under a limit stricter than the grocery sources'

#### Scenario: CT enrichment is cached for the flyer validity window
- **WHEN** a CT product enriched today for a flyer valid through Jul30 is seen again before Jul30
- **THEN** the cached enrichment is reused with no new CT request

#### Scenario: Discount depth uses the enrichment regular, not the flyer's claim
- **WHEN** a CT flyer claims a struck-through regular of 39.99 but CT enrichment says 29.99
- **THEN** discount depth is computed against 29.99
