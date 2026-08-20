# Price History

Source contracts: `specs/07-price-history/07.1`–`07.3`. Module: `modules/pricehistory`. Stats and scoring are `@pure`; the Hammer loader is `@boundary` batch tooling, not part of the daily run.

## ADDED Requirements

### Requirement: Project Hammer baseline loader
A batch loader SHALL seed price history from the open Project Hammer dataset (Voila, T&T, Loblaws, No Frills, Metro, Galleria, Walmart, Save-On-Foods; daily prices from 2024-02-28, carrying both `current_price` and `old_price`): it joins the product and raw files on product id, maps Hammer vendors to our `MerchantId`s where they overlap, derives `ProductKey`s via the normalization product key so Hammer history and first-party observations share a key space where the mapping is sound, flags rows lower-trust where Hammer's own docs warn matching is fuzzy (Loblaws/No Frills/T&T/Voila), and stamps `source = hammer` provenance so baseline history is never confused with first-party observations. (Ref: `specs/07-price-history/07.1-project-hammer-loader.spec.md`)

#### Scenario: Product and raw files join into price history rows
- **WHEN** a Hammer product file and raw file are loaded
- **THEN** price history rows are created joined on product id, each carrying current and old price where present

#### Scenario: Vendors map to merchant ids where they overlap
- **WHEN** a Hammer row for vendor "Metro" is loaded
- **THEN** it is associated with the Metro merchant id

#### Scenario: Fuzzy-matched vendors are flagged lower-trust
- **WHEN** a row from a vendor Hammer marks unreliable is loaded
- **THEN** it is stored with a reduced trust weight

#### Scenario: Hammer history is distinguishable from first-party observations
- **WHEN** history for a product is queried after loading Hammer and accumulating own observations
- **THEN** each row's provenance identifies whether it came from Hammer

### Requirement: Rolling weighted price stats
`rollingStats(obs, window, now)` SHALL compute, purely, the trailing distribution for a product key: weighted median, min, max, last seen, and n. Only observations with an effective price contribute numerically (price-absent promos count in n context only). Weighting is pinned and tested: `ScalarPrice`/High full weight; `MultiBuyUnit`/Medium and Hammer-fuzzy less; `ParsedFromText`/Low least. The weighted-median interpolation rule is pinned so results are deterministic; only observations inside the window contribute; `min ≤ weightedMedian ≤ max` whenever stats exist. (Ref: `specs/07-price-history/07.2-rolling-price-stats.spec.md`)

#### Scenario: Median and min are computed over priced observations only
- **WHEN** stats run over observations priced [2.50, 2.99, 3.49] plus one price-absent promo
- **THEN** min is 2.50 and the promo does not affect the median

#### Scenario: Higher-confidence observations weigh more
- **WHEN** the weighted median runs over 2.00 (Low), 3.00 (High), 3.00 (High)
- **THEN** the weighted median is nearer 3.00 than a naive median would be

#### Scenario: Only in-window observations contribute
- **WHEN** stats run over 12 weeks of observations with an 8-week window
- **THEN** only the last 8 weeks contribute

#### Scenario: All-price-absent history yields no numeric stats
- **WHEN** stats run over only price-absent promos
- **THEN** median, min, and max are absent while n reflects the promo count

#### Scenario: Ordering invariant holds (property)
- **WHEN** stats run over any non-empty priced set
- **THEN** min ≤ weighted median ≤ max

### Requirement: Deal quality scorer
`scoreDeal(obs, stats, enrichment)` SHALL produce a `DealVerdict` — `BestEver(sinceWeeks)`, `BelowUsual(pctBelowMedian)`, `Notable`, `AtOrAboveUsual`, `Unknown` — by the pinned, threshold-configurable rule: no effective price and no enrichment → Unknown; price ≤ trailing min → BestEver; sufficiently below weighted median → BelowUsual; at/above weighted median → AtOrAboveUsual (the false-sale detector — a flyer can shout SALE on a normal price); thin history (n below a floor) caps the verdict at Notable, never BestEver; when enrichment contradicts a struck-through flyer "regular" (the CT case), discount is computed against the enrichment regular. (Ref: `specs/07-price-history/07.3-deal-quality-scorer.spec.md`)

#### Scenario: A price at the trailing minimum is best-ever
- **WHEN** the stats min over 8 weeks is 2.50 and the current price is 2.50
- **THEN** the verdict is `BestEver` with the window in weeks

#### Scenario: A modest markdown is below-usual
- **WHEN** the weighted median is 3.00 and the current price is 2.55
- **THEN** the verdict is `BelowUsual` at about 15 percent

#### Scenario: A false sale is flagged
- **WHEN** the weighted median is 3.00 and the current price is 3.19 with sale text "SALE"
- **THEN** the verdict is `AtOrAboveUsual`

#### Scenario: Thin history never over-claims
- **WHEN** only 2 prior observations exist and the current price is below both
- **THEN** the verdict is at most `Notable`

#### Scenario: Enrichment overrides an inflated flyer regular
- **WHEN** a flyer claims regular 39.99 / sale 29.99 but enrichment says regular 29.99
- **THEN** the discount computes as roughly zero and the verdict reflects no real markdown

#### Scenario: No price and no enrichment is an honest Unknown
- **WHEN** a price-absent promo is scored with no enrichment
- **THEN** the verdict is `Unknown`
