# Normalization

Source contracts: `specs/02-normalization/02.1`–`02.7`. Module: `modules/normalization`. Every unit here is `@pure` — no IO — and the module is a primary mutation-testing target (the observation assembler especially).

## ADDED Requirements

### Requirement: Price text parser
`parsePriceToken(text, locale)` SHALL extract a `PriceToken(amount: Money, unit: Option[PriceUnit])` from a single free-text money token, handling: leading/trailing currency symbols (`$4.99`, `4,99 $`), comma-decimal French format, thousands separators (space or comma, stripped before interpreting the decimal mark), cents notation (`99¢`, `99 cents`, `.99`), and unit suffixes (`/lb`, `/kg`, `ea`, `each`, `chacun`) captured separately rather than discarded. Non-price text SHALL return `None`. Money never receives a comma. (Ref: `specs/02-normalization/02.1-price-text-parser.spec.md`)

#### Scenario: Common price tokens parse to exact cents with units
- **WHEN** `"$4.99"`, `"4,99 $"`, `"99¢"`, `".99"`, `"$1.50/lb"`, `"3,29 $/kg"`, `"$2.00 ea"` are parsed
- **THEN** the amounts are 499, 499, 99, 99, 150 (PerLb), 329 (PerKg), 200 (Each) cents

#### Scenario: Non-price text yields nothing
- **WHEN** `"see store"`, `"BOGO"`, `""`, `"free"` are parsed
- **THEN** no price token is produced

#### Scenario: French thousands and comma-decimal fully resolve before Money
- **WHEN** `"1 234,05 $"` is parsed with locale `FrCa`
- **THEN** the amount is 123405 cents and Money never receives a comma

### Requirement: Multi-buy parser
`parseMultiBuy(text, basePrice, locale)` SHALL turn `N for $X` / `N/$X` / `N pour X $` into a `MultiBuy(quantity, bundlePrice, freeQuantity, unitPrice)` with the unit price derived by half-even division; `buy N get M free` with a known base price SHALL derive the effective unit over N+M items, and without one SHALL emit structure with no unit price. Savings tails (`", save $1.98"`) are ignored. Text with no multibuy structure returns `None`. (Ref: `specs/02-normalization/02.2-multibuy-parser.spec.md`)

#### Scenario: N-for-$X yields a half-even unit price
- **WHEN** `"2 for $5"`, `"3/$5.00"`, `"2 pour 5 $"` are parsed
- **THEN** the unit prices are 250, 167, 250 cents respectively (500/3 pins half-even against a floor/round mutation)

#### Scenario: BOGO with a base price yields an effective unit price
- **WHEN** `"buy 2 get 1 free"` is parsed with base price 3.00
- **THEN** quantity 2, free quantity 1, unit price 200 cents (6.00 over 3 items)

#### Scenario: BOGO without a base price yields structure but no unit price
- **WHEN** `"buy 2 get 1 free"` is parsed with no base price
- **THEN** the free quantity is 1 and the unit price is absent

#### Scenario: Non-multibuy text returns nothing
- **WHEN** `"$4.99"`, `"50% off"`, `""`, `"save $2"` are parsed
- **THEN** no multibuy is produced

### Requirement: Percentage-off parser
`parsePercentOff(text, basePrice, locale)` SHALL interpret `N% off` / `save N%` / `N% de rabais` / `rabais de N%`, half-price forms (`1/2 price`, `moitié prix` → 50%), ranges (`up to` / `jusqu'à` → marked `isUpperBound`), and bare free-item claims (`free`, `gratuit` → 100%, but never a BOGO — that's the multibuy parser's). With a base price the sale price is computed; without, the rate is recorded and `salePrice` is absent. Loyalty-points text (`"25 points"`) SHALL fall through cleanly to nothing. (Ref: `specs/02-normalization/02.3-percentage-off-parser.spec.md`)

#### Scenario: Percent expressions parse to a rate
- **WHEN** `"50% off"`, `"40% de rabais"`, `"up to 40% off"`, `"moitié prix"` are parsed
- **THEN** rates are 50, 40, 40 (upper-bound), 50 respectively

#### Scenario: With a base price the sale price is computed
- **WHEN** `"25% off"` is parsed with base price 20.00
- **THEN** the rate is 25 and the sale price is 1500 cents

#### Scenario: A bare free claim is 100% off but a BOGO is not
- **WHEN** `"free"` is parsed with no base price
- **THEN** the rate is 100
- **WHEN** `"buy 2 get 1 free"` is parsed
- **THEN** no percent-off is produced

#### Scenario: Loyalty points fall through
- **WHEN** `"25 points"` is parsed
- **THEN** no percent-off is produced

### Requirement: Unit price calculator
`parseSize(name, locale)` SHALL extract sizes from item names — `4 L`, `500 g`, `1.5kg`, multipacks (`12 x 355 mL` totalled), `paquet de 6` / `pack of 6`, `dozen` — normalized to standard units (volumes per litre, weights per kg, countables per item), returning `None` when no size is parseable (common; not an error). `unitPrice(price, size)` SHALL compute the per-standard-unit price with half-even rounding. Multiple size-like tokens in a name SHALL take the first and mark lower confidence. (Ref: `specs/02-normalization/02.4-unit-price-calculator.spec.md`)

#### Scenario: Sizes parse to quantity, standard unit, and pack count
- **WHEN** `"Natrel Milk 4 L"`, `"Beurre 500 g"`, `"Pepsi 12 x 355 mL"`, `"Eggs, dozen"` are parsed
- **THEN** the sizes are 4 PerLitre ×1, 0.5 PerKg ×1, 4.26 PerLitre ×12, 12 PerItem ×12

#### Scenario: Names with no size return nothing
- **WHEN** `"Assorted Hand Tools"` is parsed
- **THEN** no size is produced

#### Scenario: Price plus size yields a per-standard-unit price
- **WHEN** 499 cents is combined with 4 PerLitre, and 500 cents with 0.5 PerKg
- **THEN** the unit prices are 125 cents/L and 1000 cents/kg

### Requirement: Bilingual name splitter
`splitBilingual(raw)` SHALL split jammed-together bilingual names on explicit separators (pipe, spaced slash, spaced dash, newline), assigning sides to fr/en by a cheap diacritic + stopword heuristic (not left/right position). A single-language name goes in its detected language only — never fabricate a translation. When ambiguous, the whole string SHALL be placed in both forms with `Low` confidence (safe for matching). The input rawName is never mutated. (Ref: `specs/02-normalization/02.5-bilingual-name-splitter.spec.md`)

#### Scenario: A pipe-separated bilingual name splits by detected language
- **WHEN** `"LAIT FINEMENT FILTRÉ NATREL | NATREL FINE-FILTERED MILK"` is split
- **THEN** fr is the LAIT side, en is the MILK side, confidence High

#### Scenario: A single-language name is detected, not translated
- **WHEN** `"MASTERCRAFT 5-Shelf Resin Rack"` / `"Beurre d'arachide croquant"` are split
- **THEN** only the English / French form respectively is populated

#### Scenario: An ambiguous name lands in both forms with low confidence
- **WHEN** `"Cola 2L"` is split
- **THEN** both forms contain `"Cola 2L"` and confidence is Low

### Requirement: Observation assembler with pinned precedence
`assembleObservation` SHALL compose the parsers into one deterministic `FlyerItem → PriceObservation` function with this pinned precedence for `effectivePrice`/`priceBasis`: (1) scalar `current_price` → `ScalarPrice`/High (a coexisting sale story is recorded but never overrides); (2) multibuy with derivable unit price → `MultiBuyUnit`/Medium; (3) percent-off with `original_price` → `ParsedFromText`/Medium; (4) percent-off without base → `effectivePrice = None`, `PercentOffUnknown`; (5) bare price token in name → `ParsedFromText`/Low; (6) else `None`/`Unknown` with any saleText preserved. At every step a parseable size attaches a unit price; the bilingual split and product key are always applied; observation confidence is the minimum of price-derivation and name-split confidence. The ladder SHOULD be an ordered list of strategies so re-ordering is a one-line change. (Ref: `specs/02-normalization/02.6-observation-assembler.spec.md`)

#### Scenario: A scalar price wins over a coexisting sale story
- **WHEN** an item has `current_price` 4.99 and `sale_story` `"2 for $5"`
- **THEN** the effective price is 4.99, basis `ScalarPrice`, and the sale story is still recorded as sale text

#### Scenario: A multibuy with no scalar price yields a derived unit price
- **WHEN** an item has no `current_price` and `sale_story` `"2 for $5"`
- **THEN** the effective price is 250 cents, basis `MultiBuyUnit`, confidence Medium

#### Scenario: Percent-off without a base records a promo but no price
- **WHEN** an item has no prices and `sale_story` `"50% off"`
- **THEN** the effective price is absent, basis `PercentOffUnknown`, and the sale text records the rate

#### Scenario: A loyalty-points offer becomes an opaque promo observation
- **WHEN** an item has no prices and `sale_story` `"25 points"`
- **THEN** the effective price is absent, basis `Unknown`, sale text `"25 points"`

#### Scenario: Confidence is the minimum across price and name-split
- **WHEN** a scalar-priced (High) item has a Low-confidence name split
- **THEN** the observation confidence is Low

### Requirement: Stable product key
`productKey(merchantId, name, size)` SHALL derive a deterministic, opaque, merchant-scoped key from the attributes that don't change week to week: merchant + normalized name tokens (via the watchlist text normalizer: accent-fold, lowercase, drop punctuation and stopwords, keep order) + size. Different sizes and different merchants yield different keys; a missing size still yields a deterministic key. Cross-language and cross-merchant identity are explicitly out of scope (price-history's concern). The normalization version SHALL be stamped into the key (e.g. `v1:` prefix) so improvements can migrate history deliberately. (Ref: `specs/02-normalization/02.7-product-key.spec.md`)

#### Scenario: The same product across weeks yields the same key
- **WHEN** keys are derived for merchant 123 `"Natrel Milk 4 L"` and merchant 123 `"NATREL MILK 4L"` (also accent/case variants like `"LAIT NATREL 4 L"` vs `"lait natrel 4 l"`)
- **THEN** the keys are equal

#### Scenario: Different sizes or merchants yield different keys
- **WHEN** keys are derived for 2L vs 4L milk at merchant 123, and for the same 4L milk at merchants 123 vs 456
- **THEN** the keys differ in both cases

#### Scenario: Key derivation is a pure function (property)
- **WHEN** any (merchant, name, size) is keyed repeatedly
- **THEN** every derivation is identical
