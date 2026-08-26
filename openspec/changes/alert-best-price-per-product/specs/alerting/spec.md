# Alerting

Module: `modules/alerting`. Dedup and alert construction stay pure — prior-alert state and the candidate set are passed in. Blocked on Ariadne issuing cross-merchant `ProductId`s; see `proposal.md`.

## MODIFIED Requirements

### Requirement: Alert dedup

Dedup SHALL key on `(watchId, productId)`. The key SHALL contain no validity window and nothing else derived from the set of observations under comparison.

The prior-alert record SHALL carry `expiresAt` — the `validTo` of the winning observation — and a deal SHALL be treated as news again once `now` is past it. The record SHALL also carry the best price, the winning store, and `bestSince`.

The exclusion of the window from the key is a requirement, not an implementation note. Flyer selection is incremental, so a product's candidate offers grow across runs within one flyer week; any window derived from them (intersection, union, or the winning offer's own) changes as offers arrive, which changes the key, which orphans the record filed under the previous key and re-alerts. The prior version keyed on the observation's `validFrom`/`validTo` and therefore produced one key per merchant.

Replaces the previous key `(watchId, productKey, flyerValidityWindow)`, whose `productKey` was merchant-scoped, so one product at two merchants was two keys and two alerts.

#### Scenario: One product at several merchants alerts once
- **WHEN** a watched product is on sale at three merchants within the same window
- **THEN** exactly one alert is produced

#### Scenario: Differing flyer windows do not split the key
- **WHEN** the same product is on sale at merchant A for [Jul23,Jul30] and merchant B for [Jul25,Aug01]
- **THEN** both observations resolve to the same `AlertKey`
- **AND** exactly one alert is produced

#### Scenario: A later-arriving offer does not re-alert an unchanged product
- **GIVEN** a product alerted on Jul 23 from the only merchant fetched so far
- **WHEN** a further merchant's flyer is fetched on Jul 25 offering the same product at a higher price
- **THEN** no alert is produced
- **AND** the record filed on Jul 23 is still the one consulted

#### Scenario: A deal is news again once it has expired
- **WHEN** a product whose record expired on Jul 30 is on sale on Jul 31
- **THEN** it is alerted

#### Scenario: A better price extends the expiry
- **WHEN** a product alerted at 4.99 expiring Jul 30 is beaten by 3.99 on a flyer running to Aug 01
- **THEN** it is re-alerted
- **AND** the record now expires Aug 01

#### Scenario: A better price at a different merchant re-alerts
- **WHEN** a product alerted at 4.99 from merchant A is then seen at 3.99 from merchant B within the window
- **THEN** it is re-alerted as improved, naming merchant B

#### Scenario: A worse price at a different merchant does not re-alert
- **WHEN** a product alerted at 3.99 from merchant B is then seen at 4.99 from merchant A within the window
- **THEN** it is not re-alerted

### Requirement: Best price selection

Where a watch matches several observations of one product within a window, the alert SHALL report the observation with the lowest `effectivePrice`. Comparison is on effective price rather than unit price because a `ProductId` implies a single pack size (Ariadne, 2026-08-26), so the candidates are like for like.

An observation with no effective price SHALL NOT win a comparison against one that has a price, because "no price" is unknown, not free — the same distinction the run report draws between an unknown audience and an audience of zero.

The selection SHALL be pure: candidates in, winner and beaten set out.

#### Scenario: The cheapest observation wins
- **WHEN** one product is matched at 4.99, 3.99 and 5.49 within a window
- **THEN** the alert reports 3.99 and the merchant offering it

#### Scenario: A priceless observation never wins
- **WHEN** a product is matched at 4.99 at one merchant and with no parseable price at another
- **THEN** the alert reports 4.99
- **AND** the priceless observation does not win by comparing as lower

#### Scenario: No candidate has a price
- **WHEN** every matched observation of a product lacks an effective price
- **THEN** the existing no-price promo behaviour applies and the alert renders sale text in place of a fabricated price

### Requirement: Alert model and rendering

`Alert` SHALL carry watch label, the winning merchant, preferred-locale item name, optional price, sale text, the `DealVerdict`, valid-to, score, `bestSince`, **and the comparison that selected it**: how many other offers of the same product were considered, and the next-best price **together with the merchant offering it**.

The comparison travels with the alert for two reasons.

It makes the claim checkable. Without it a reader cannot distinguish a correct comparison across four merchants from a comparison over the single observation that happened to be seen, and the two are indistinguishable in every other respect — including in a run report that reads as healthy.

It also keeps the alert usable. Per-listing alerting let a reader see every offer and choose the one at a store they were going to anyway; per-product alerting names the cheapest, which may be a merchant they never visit, and suppresses the one they would have acted on. Naming the runner-up **and its merchant** returns that choice without requiring any watch to be configured first — which matters, because merchant scoping exists on `WatchItem` and no live watch uses it.

Both renders change. `renderPlain(locale)` stays one locale-aware line, price-first, keeping the verdict phrase. `renderStructured` gains the winning merchant and the comparison, and is a consumer-visible change for the Home Assistant sink.

#### Scenario: An alert says what it beat, and where
- **WHEN** a product is alerted at 3.99 from merchant B, having been matched at 4.99 from merchant A and 5.49 from merchant C
- **THEN** the alert reports that 2 other offers were considered
- **AND** reports the next best as 4.99 at merchant A

#### Scenario: A sole offer says so rather than implying a comparison
- **WHEN** a product is alerted and was matched at exactly one merchant
- **THEN** the alert reports that no other offers were considered
- **AND** does not render as though it won a comparison

#### Scenario: No beaten price is lower than the winner
- **WHEN** any alert is constructed
- **THEN** every price in its beaten set is greater than or equal to the reported price

### Requirement: An unchanged price does not re-alert, but says how long it has held

A product whose record has expired and which is offered again at a price **no better than** the one last reported SHALL NOT produce an alert. Re-announcing an unchanged price on every flyer renewal is noise. This replaces the previous rule that a new flyer window is news again, which was not a deliberate choice so much as a consequence of the window being part of the key.

The fact SHALL NOT be lost with the alert. The record carries `bestSince` — when the current best price was first reported — updated when the price changes and carried forward when it does not. Anywhere a price is presented, whether in an alert or in the read model, it SHALL be accompanied by `bestSince`.

#### Scenario: A renewed flyer at the same price is silent
- **GIVEN** a product last alerted at 4.99, whose record has expired
- **WHEN** the next flyer offers it again at 4.99
- **THEN** no alert is produced
- **AND** the record's expiry advances to the new offer
- **AND** `bestSince` still reports the date 4.99 was first reported

#### Scenario: A renewed flyer at a worse price is also silent
- **GIVEN** a product last alerted at 4.99, whose record has expired
- **WHEN** the next flyer offers it at 5.49
- **THEN** no alert is produced

#### Scenario: bestSince moves when the price moves
- **GIVEN** a product reporting 4.99 since Jul 23
- **WHEN** it is alerted at 3.99 on Aug 02
- **THEN** `bestSince` becomes Aug 02
- **AND** does not remain Jul 23

#### Scenario: An improvement says what the old price was and how long it stood
- **GIVEN** a product reporting 4.99 since Jul 23
- **WHEN** it is alerted at 3.99
- **THEN** the alert reports the previous price as 4.99 held since Jul 23

### Requirement: Losing offers are not suppressions

An offer that lost a best-price comparison SHALL NOT be counted in `suppressedByReason`. The suppression map exists to explain *silence* to someone tuning a watch, and is surfaced in the run report and the UI; the product was alerted, so counting each losing merchant as a suppression would inflate the map with events that are not silence and make a working comparison look like aggressive filtering.

#### Scenario: Losing offers do not appear in the suppression map
- **WHEN** a product is alerted having beaten three other offers
- **THEN** the run report's suppression counts are unchanged by those three
- **AND** the matched count still reflects every matching observation
