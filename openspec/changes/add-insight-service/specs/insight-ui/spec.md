# Insight UI

A browser interface over `insight-api`. Presentation only: it performs no analysis of its own, so the number a chart draws and the number an alert quotes cannot drift apart.

## ADDED Requirements

### Requirement: Reachable on the tailnet only
The UI SHALL be exposed on the tailnet and SHALL NOT be reachable publicly. It displays a household's grocery buying patterns, which is not public data.

#### Scenario: Not served publicly
- **WHEN** the ingress is configured
- **THEN** it answers only on a tailnet hostname, following the convention used for the HermesMQ console

### Requirement: Product price history view
The UI SHALL chart a product's price over time with the rolling median drawn behind the points, and SHALL render lower-confidence observations distinguishably from scalar prices.

#### Scenario: Confidence is visible, not averaged away
- **WHEN** a series mixes ScalarPrice and ParsedFromText observations
- **THEN** the lower-confidence points are visually distinct, so a median pulled by a guessed price is not read as fact

#### Scenario: The verdict is stated in the same words as the alerts
- **WHEN** a product's latest observation is the cheapest in the window
- **THEN** the view uses the phrase `DealVerdict` produces, so the UI and an alert never describe the same fact differently

### Requirement: Run report view
The UI SHALL show the latest run's report, with suppression broken down by reason and the delivered/suppressed/matched figures reconciled.

#### Scenario: A delivery shortfall is apparent
- **WHEN** matches exceed delivered plus suppressed
- **THEN** the view makes the shortfall visible rather than presenting a green summary

### Requirement: Watch health view
The UI SHALL show, per watch, what it matched and why those matches did not alert.

#### Scenario: Tuning a noisy watch is a reading task
- **WHEN** a watch matches many unwanted items
- **THEN** the view shows the matched item names, so exclusion terms can be chosen by reading rather than by grepping logs

### Requirement: Writes are limited to the watchlist
The UI MAY create, pause and delete watches. It SHALL NOT offer any other
mutation — no acknowledging alerts, no triggering runs, no editing observations.

This reverses the original "no writes" decision, which predicted this exact
request ("just let me pause a watch from here") and pre-answered it. What does
NOT reverse is the reason: the price history cannot be rebuilt, because flyers
expire. The watchlist can — it is typed in, and worth what it costs to retype.
So the write path holds a role granted INSERT/UPDATE/DELETE on `watch_item`
alone, and the read path keeps a connection that cannot write at all.

#### Scenario: The irreplaceable data stays unwritable
- **WHEN** the watch-editing role attempts to write price_observation, raw_response or alert_ledger
- **THEN** PostgreSQL refuses it, because those grants were never made

#### Scenario: No other mutating affordance is presented
- **WHEN** any view is rendered
- **THEN** the only controls that write are the watch ones

#### Scenario: A deployment without the write role is read-only
- **WHEN** no watch password is configured
- **THEN** the write routes are not mounted at all, rather than mounted and refusing
