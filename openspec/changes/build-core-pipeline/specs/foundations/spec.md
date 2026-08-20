# Foundations

Source contracts: `specs/00-foundations/00.1`–`00.5`. Module: `modules/foundations`. All units here are `@pure` and are primary mutation-testing targets.

## ADDED Requirements

### Requirement: Money as exact cents
`Money` SHALL wrap a `Long` count of minor units (cents) plus an explicit `Currency` (v1: CAD only). Construction from a decimal string or `BigDecimal` SHALL be total — returning `Either[MoneyError, Money]`, never throwing, never silently truncating. Division across a count SHALL use banker's rounding (half-even). Formatting SHALL be the inverse of parsing for any value that parsed cleanly. The constructor SHALL be private; construction only via smart constructors. (Ref: `specs/00-foundations/00.1-money.spec.md`)

#### Scenario: Well-formed decimal strings parse to exact cents
- **WHEN** `"4.99"`, `"10"`, `"10.50"`, `"0"`, or `"1234.05"` is parsed
- **THEN** it succeeds with exactly 499, 1000, 1050, 0, and 123405 cents respectively

#### Scenario: Malformed or out-of-domain amounts are rejected, not coerced
- **WHEN** `"abc"` / `""` (NotANumber), `"4.999"` (TooManyDecimalPlaces), `"-1.00"` (Negative), or `"4,99"` (NotANumber — comma-decimal is normalization's concern) is parsed
- **THEN** the named `MoneyError` is returned and no `Money` is produced

#### Scenario: Parse/format round-trips
- **WHEN** any valid `Money` is formatted and the result parsed back
- **THEN** the identical cent count is recovered (property test; e.g. `"7.05"` formats back to `"7.05"`)

#### Scenario: Division uses half-even rounding
- **WHEN** 500 cents is divided across 3 units
- **THEN** each unit is 167 cents
- **WHEN** 5 cents is divided across 2 units
- **THEN** each unit is 2 cents (half-even rounds down to even)

#### Scenario: Currencies never silently mix
- **WHEN** arithmetic is attempted between two `Money` values of different currencies
- **THEN** the operation is rejected at the type or value level and no result is produced

### Requirement: Canadian postal code
`PostalCode` SHALL validate the Canadian `A1A 1A1` format (space optional, case-insensitive on input), excluding letters Canada Post never uses (`D F I O Q U` anywhere; `W Z` as first letter). It SHALL canonicalize to upper case with no internal space (`H2X1Y6`) — the form the Flipp query string uses — with a separate `withSpace` display renderer, and SHALL expose the Forward Sortation Area (first three characters). The illegal-letter set SHALL live in one named constant. (Ref: `specs/00-foundations/00.2-postal-code.spec.md`)

#### Scenario: Valid postal codes parse and canonicalize
- **WHEN** `"H2X 1Y6"`, `"h2x1y6"`, or `"H2X1Y6"` is parsed
- **THEN** parsing succeeds with canonical form `"H2X1Y6"` and FSA `"H2X"`

#### Scenario: Structurally invalid inputs are rejected
- **WHEN** `"12345"`, `"H2X"`, `"H2X-1Y6"`, `"HH2 1Y6"`, or `""` is parsed
- **THEN** it fails with `WrongShape`

#### Scenario: Letters excluded by Canada Post are rejected
- **WHEN** `"D2X 1Y6"`, `"H2I 1Y6"`, `"W2X 1Y6"` (W invalid as first letter), or `"H2X 1O6"` is parsed
- **THEN** it fails with `IllegalLetter` naming the offending letter

#### Scenario: Display form re-inserts the space
- **WHEN** a postal code parsed from `"H2X1Y6"` renders its display form
- **THEN** the result is `"H2X 1Y6"`

### Requirement: Locale
`Locale` SHALL be a closed enum of exactly `fr-ca` and `en-ca`, each carrying the exact query-string rendering the Flipp endpoints expect. (Ref: `specs/00-foundations/00.3-locale-and-bilingual-text.spec.md`)

#### Scenario: Locale renders to the exact endpoint query value
- **WHEN** `FrCa` or `EnCa` is rendered for a query string
- **THEN** the value is `"fr-ca"` / `"en-ca"` respectively

### Requirement: BilingualText container
`BilingualText` SHALL hold an optional French and optional English form with accessors: `primary(preferred)` (preferred language, else the other, else none), `anyForm` (English, else French, else none), and `forms` (every present form, deduplicated — the matcher's input). The splitting algorithm lives in normalization (02.5); this is only the type, so foundations stays dependency-free. (Ref: `specs/00-foundations/00.3-locale-and-bilingual-text.spec.md`)

#### Scenario: Primary prefers the requested language and falls back
- **WHEN** a text with fr `"lait"` and en `"milk"` is asked for primary preferring `FrCa`
- **THEN** the result is `"lait"`
- **WHEN** a text with only en `"milk"` is asked for primary preferring `FrCa`
- **THEN** the result is `"milk"`
- **WHEN** both forms are absent
- **THEN** the result is none

#### Scenario: forms exposes every present language deduplicated
- **WHEN** a text with fr `"lait"` and en `"milk"` is asked for its forms
- **THEN** the forms are exactly `["lait", "milk"]`
- **WHEN** both languages carry identical text `"Coca-Cola"`
- **THEN** the forms are exactly `["Coca-Cola"]`

### Requirement: Core domain model
The system SHALL define immutable domain types with honest optionality: `MerchantId`, `FlyerId`, `Merchant`, `Flyer`, `FlyerItem`, and `PriceObservation` with `PriceBasis` (`ScalarPrice | MultiBuyUnit | PercentOffUnknown | ParsedFromText | Unknown`) and `Confidence` (`High | Medium | Low`). `effectivePrice` SHALL be `None` iff no price could be responsibly derived; `rawName` SHALL never be modified; `validFrom < validTo` SHALL hold for any accepted Flyer/FlyerItem. (Ref: `specs/00-foundations/00.4-domain-model.spec.md`)

#### Scenario: An item with no derivable price is representable
- **WHEN** a `FlyerItem` with no current price and saleStory `"50% off"` is normalized
- **THEN** the observation has no effective price, basis `PercentOffUnknown`, and is still valid and storable

#### Scenario: Raw upstream name is preserved verbatim
- **WHEN** a `FlyerItem` with rawName `"  Natrel 3.25%  |  LAIT 3.25%  "` flows through normalization
- **THEN** the resulting observation still carries that exact rawName unchanged

#### Scenario: Non-positive validity windows are rejected
- **WHEN** a Flyer is built with `validFrom >= validTo`
- **THEN** it is rejected as a domain object

#### Scenario: A clean scalar price yields ScalarPrice basis and High confidence
- **WHEN** a `FlyerItem` with current price 4.99 and no ambiguity is normalized
- **THEN** the effective price is 4.99, basis `ScalarPrice`, confidence `High`

### Requirement: Error taxonomy with pinned retriability
The system SHALL define one sealed hierarchy `DealWatchError` — transport (`HttpStatus`, `Timeout`, `Transport`, `BotWall`), decode (`Decode(source, pointer, reason)`), domain (`InvalidDomain`), persistence (`StoreConflict`, `StoreUnavailable`) — where every case carries `retriable: Boolean` and a `context: Map[String, String]` sufficient to reconstruct what happened without re-hitting the network. Retriability SHALL be exactly: 5xx/429/Timeout/Transport/StoreUnavailable retriable; other 4xx/BotWall/Decode/InvalidDomain/StoreConflict not. `BotWall` is its own case so the orchestrator can react distinctly (the "Flipp added auth" signal). (Ref: `specs/00-foundations/00.5-error-model.spec.md`)

#### Scenario: Each error declares the correct retriability
- **WHEN** the retriable flag of each case is inspected
- **THEN** `HttpStatus(500/503)`, `HttpStatus(429)`, `Timeout`, `Transport`, `StoreUnavailable` are `true` and `HttpStatus(404)`, `BotWall`, `Decode`, `InvalidDomain`, `StoreConflict` are `false`

#### Scenario: A bot wall is non-retriable and flagged for the operator
- **WHEN** a `BotWall` error with signal `"cf-chl-bypass"` is produced
- **THEN** it is not retriable, is classified as operator-attention, and its context contains the signal

#### Scenario: Every error carries reconstructable context
- **WHEN** any `DealWatchError` is inspected
- **THEN** its context map is non-null, and a `Decode` error's context identifies the source and the JSON pointer that failed
