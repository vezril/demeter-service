# Watchlist

Source contracts: `specs/04-watchlist/04.1`–`04.4`. Module: `modules/watchlist`. All units `@pure`.

## ADDED Requirements

### Requirement: Watch item model
`WatchItem` SHALL capture what the user watches: a label, a non-empty list of match terms in any language (stored raw; normalization happens at match time), a merchant scope (`Set[MerchantId]` where empty means any merchant), alerting conditions (`maxPrice`, `requireSale`, `minDiscountPct` — held and validated here, evaluated by alerting), and an `active` flag. Validation SHALL reject an item with no terms; an inactive item is never in scope for matching. (Ref: `specs/04-watchlist/04.1-watch-item-model.spec.md`)

#### Scenario: A watch item requires at least one term
- **WHEN** a watch item is constructed with no terms
- **THEN** validation rejects it

#### Scenario: An empty merchant set means any merchant
- **WHEN** a watch item with an empty merchant set is checked against merchant 123
- **THEN** the merchant is in scope

#### Scenario: A scoped watch item only includes its listed merchants
- **WHEN** a watch item scoped to {123, 456} is checked against merchant 789
- **THEN** 789 is out of scope while 123 is in scope

#### Scenario: An inactive watch item is skipped
- **WHEN** the matcher considers an inactive watch item
- **THEN** it is skipped

### Requirement: Matching text normalizer
One shared normalization function (also reused by the product key) SHALL apply, in pinned order: (1) Unicode NFKD + strip combining marks (é→e, ç→c, Œ→oe), (2) lowercase, (3) punctuation/symbols → spaces, (4) collapse whitespace and trim, (5) drop a configurable bilingual stopword set ("de", "the", "avec", "with", "&"), yielding a token list and joined string. Normalization SHALL be idempotent. (Ref: `specs/04-watchlist/04.2-text-normalizer.spec.md`)

#### Scenario: Accents and case are folded
- **WHEN** `"Café"`, `"CRÈME GLACÉE"`, `"Coca-Cola"`, `"Œufs"` are normalized
- **THEN** the results are `"cafe"`, `"creme glacee"`, `"coca cola"`, `"oeufs"`

#### Scenario: Stopwords are dropped
- **WHEN** `"Beurre de pomme with cinnamon"` is normalized with stopwords including "de" and "with"
- **THEN** the tokens are `["beurre","pomme","cinnamon"]`

#### Scenario: Normalization is idempotent (property)
- **WHEN** any text is normalized twice
- **THEN** both results are identical

### Requirement: Layered matcher
Matching a `PriceObservation` to a `WatchItem` SHALL proceed in layers: (1) merchant scope — out of scope short-circuits to no match; (2) token containment — a term matches when all its normalized tokens appear in some normalized bilingual form of the item name; (3) fuzzy fallback — bounded edit distance (Jaro-Winkler ≥ threshold or Levenshtein ≤ k) against a contiguous window, catching spelling variance without matching near-misses. Terms within a watch are OR'd; matching runs against every language form so French and English terms both hit. Each match carries a `MatchScore`. (Ref: `specs/04-watchlist/04.3-matcher.spec.md`)

#### Scenario: Either language's term matches via bilingual forms
- **WHEN** terms `"milk"` and `"lait"` are each matched against an observation whose forms are `["lait natrel", "natrel milk"]`
- **THEN** both are matches

#### Scenario: Token containment ignores word order and extra words
- **WHEN** term `"milk 4l"` is matched against form `"natrel fine filtered milk 4 l"`
- **THEN** it is a match

#### Scenario: Out-of-scope merchant short-circuits
- **WHEN** a watch scoped to merchant 999 is matched against a name-matching observation from merchant 123
- **THEN** it is not a match

#### Scenario: Fuzzy fallback catches variance but not near-misses
- **WHEN** `"yogourt"` vs `"greek yoghurt"`, `"milk"` vs `"milkshake mix"`, and `"chicken breast"` vs `"chicken broth"` are matched
- **THEN** the results are match, no match, and no match respectively

### Requirement: Match scoring
Each match SHALL carry a `MatchScore(textScore, confidence, priceRank)` with a documented, configurable combined weighting (default text 0.4, confidence 0.3, price 0.3): exact containment scores 1.0 text, fuzzy scores by similarity; within a match group the cheapest effective price ranks highest and price-absent observations rank last; the combined score stays in 0..1. (Ref: `specs/04-watchlist/04.4-match-scoring.spec.md`)

#### Scenario: Exact beats fuzzy on text score
- **WHEN** an exact-containment match and a fuzzy match for the same term are scored
- **THEN** the exact match has the higher text score

#### Scenario: Higher confidence outranks lower at equal text score
- **WHEN** two equal-text matches differ only in High vs Low observation confidence
- **THEN** the High-confidence match ranks higher

#### Scenario: Cheapest ranks first, unknown price last
- **WHEN** matches priced 2.50, 2.99, and unknown are ranked by price
- **THEN** 2.50 ranks first and the unknown-price match ranks last

#### Scenario: Combined score stays within 0..1 (property)
- **WHEN** any in-range component scores are combined
- **THEN** the result is between 0 and 1
