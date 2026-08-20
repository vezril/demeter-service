# Alerting

Source contracts: `specs/05-alerting/05.1`–`05.5`. Module: `modules/alerting`. Decision/dedup/render are `@pure`; sinks are `@boundary` (verified against fakes, never live endpoints).

## ADDED Requirements

### Requirement: Deal decision
`decide(match, watch, verdict)` SHALL turn a match into `Alert` or `Suppress(reason)` by the pinned rule: (1) if `maxPrice` is set and the effective price exceeds it → suppress ("above max price"); an observation with no price passes this gate only when `maxPrice` is unset; (2) if `requireSale`, the history verdict must be at least Notable — a price at/above its own history is not a sale; (3) if `minDiscountPct` is set, discount depth (from original price or history baseline) must meet it; (4) all set conditions passing → alert with score. Suppress reasons feed diagnostics. (Ref: `specs/05-alerting/05.1-deal-decision.spec.md`)

#### Scenario: At-or-below max price with a good verdict alerts
- **WHEN** a watch has maxPrice 3.00 and requireSale, and a matched observation is priced 2.50 with a good-deal verdict
- **THEN** the decision is an alert

#### Scenario: Above max price is suppressed
- **WHEN** the observation is priced 3.49 against maxPrice 3.00
- **THEN** it is suppressed with reason "above max price"

#### Scenario: requireSale suppresses a normal-priced match
- **WHEN** requireSale is true and the verdict is at-or-above-history
- **THEN** it is suppressed with reason "not a sale"

#### Scenario: A no-price promo passes only without a max price
- **WHEN** a `PercentOffUnknown` observation meets a watch with no maxPrice and requireSale false
- **THEN** it is an alert
- **WHEN** the same watch sets a maxPrice
- **THEN** it is suppressed ("price unknown, max price required")

#### Scenario: minDiscountPct gates on discount depth
- **WHEN** a watch requires ≥20% off and the observation is 10% below baseline
- **THEN** it is suppressed ("discount below threshold")

### Requirement: Alert dedup
Dedup SHALL key on `(watchId, productKey, flyerValidityWindow)`: the same deal in the same window alerts once; a new window for the same product is news again; a price drop below the previously alerted price within the window re-alerts as an improved deal; a price rise does not. The decision is pure — prior-alert state is passed in. (Ref: `specs/05-alerting/05.2-alert-dedup.spec.md`)

#### Scenario: The same deal in the same window alerts only once
- **WHEN** a deal already alerted for (W, P, [Jul23,Jul30]) appears again in that window
- **THEN** it is not re-alerted

#### Scenario: A new flyer window alerts again
- **WHEN** the product alerted for [Jul16,Jul23] is on sale again in [Jul23,Jul30]
- **THEN** it is alerted

#### Scenario: A price drop re-alerts, a rise does not
- **WHEN** a deal alerted at 2.99 drops to 2.50 within the window
- **THEN** it is re-alerted as improved
- **WHEN** a deal alerted at 2.50 shows 2.99 within the window
- **THEN** it is not re-alerted

### Requirement: Alert model and rendering
`Alert` SHALL carry watch label, merchant, preferred-locale item name, optional price, sale text, the `DealVerdict`, valid-to, and score, with two pure renders: `renderPlain(locale)` — one locale-aware line, price-first when a price exists, including the verdict phrase (the actionable part), sale text in place of a fabricated price for no-price promos — and `renderStructured` — machine-readable JSON for the Home Assistant sink. (Ref: `specs/05-alerting/05.3-alert-model-and-render.spec.md`)

#### Scenario: A priced deal renders price, merchant, and verdict
- **WHEN** an alert for "Milk 4L" at Metro, 2.50, verdict "cheapest in 8 weeks" renders plain in English
- **THEN** the text contains "2.50", "Metro", and "cheapest in 8 weeks"

#### Scenario: A no-price promo renders sale text, never a fabricated price
- **WHEN** an alert with no price and sale text "50% off" renders plain
- **THEN** the text contains "50% off" and no fabricated price

#### Scenario: French locale renders French conventions
- **WHEN** an alert renders plain with locale fr-ca
- **THEN** price and date follow French-Canadian formatting

#### Scenario: Structured render carries machine-readable fields
- **WHEN** any alert renders structured
- **THEN** the JSON includes item, merchant, price cents, verdict, and valid-to

### Requirement: Home Assistant sink
The primary `AlertSink` SHALL deliver to the operator's self-hosted Home Assistant via whichever configured mechanism: POST to an HA webhook or publish to an MQTT topic. The target URL/topic comes from config only — never derived from flyer content. Delivery failures retry per the HTTP policy; persistent failure falls through to a fallback sink and never crashes the run. Delivered alerts are recorded so dedup state persists. (Ref: `specs/05-alerting/05.4-home-assistant-sink.spec.md`)

#### Scenario: A webhook delivery posts the structured alert
- **WHEN** an alert is delivered through a webhook-configured sink
- **THEN** a POST is made to the configured webhook URL with the structured alert JSON body

#### Scenario: A transient HA outage retries then falls back
- **WHEN** HA returns 503 on every attempt and a fallback sink is configured
- **THEN** delivery is retried per policy and on exhaustion the alert is handed to the fallback

#### Scenario: The sink never posts to a target from flyer content
- **WHEN** an alert whose item text contains a URL is delivered
- **THEN** the delivery target is the configured HA endpoint only

### Requirement: Fallback sink chain
A `ChainSink` SHALL compose the primary sink with ordered fallbacks (ntfy, Telegram, email — all opt-in, operator-configured): deliver to the first sink that succeeds, never fan out to all; advance past a sink only when it fails after retries; surface a total failure across all sinks as a run-health signal identifying the sinks attempted, never swallow it. (Ref: `specs/05-alerting/05.5-fallback-sinks.spec.md`)

#### Scenario: The chain stops at the first success
- **WHEN** the chain [HA, ntfy, email] delivers and HA succeeds
- **THEN** only HA is called

#### Scenario: The chain advances past a failing sink
- **WHEN** HA fails after retries and ntfy succeeds
- **THEN** ntfy delivers and email is not called

#### Scenario: Total failure is surfaced
- **WHEN** every sink in the chain fails
- **THEN** the failure is recorded as a run-health signal naming the attempted sinks
