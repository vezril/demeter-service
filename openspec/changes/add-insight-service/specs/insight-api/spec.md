# Insight API

Module: `modules/insight`. Read-only HTTP over the existing PostgreSQL schema. Every derived number is computed by `pricehistory`, never reimplemented. Queries are `@boundary`; anything shaping a response is `@pure`.

## ADDED Requirements

### Requirement: Read-only database access
The service SHALL connect with a PostgreSQL role granted `SELECT` and nothing else, and SHALL expose no endpoint that writes. Read-only is enforced at the database, not by convention, because the price history cannot be rebuilt once flyers expire.

#### Scenario: The service cannot write even if asked to
- **WHEN** the service attempts any INSERT, UPDATE or DELETE against the demeter database
- **THEN** PostgreSQL refuses it on the role's privileges

#### Scenario: No write endpoints exist
- **WHEN** the route table is enumerated
- **THEN** every route is a GET

### Requirement: Product price history series
`GET /v1/products/{productKey}/history` SHALL return the observation series for a product key over a requested window, each point carrying its observed time, effective price, unit price where derivable, merchant, and `price_confidence`, together with the rolling statistics and deal verdict computed by `pricehistory` for the most recent point. Confidence travels per point because a Low-confidence parsed-from-text price drawn identically to a scalar price misleads.

#### Scenario: A series carries per-point confidence
- **WHEN** history is requested for a product with both ScalarPrice and ParsedFromText observations
- **THEN** each point reports its own confidence rather than a single figure for the series

#### Scenario: Statistics come from pricehistory
- **WHEN** the rolling median for a series is returned
- **THEN** it equals `PriceStats.rollingStats` over the same observations and window

#### Scenario: A verdict is included
- **WHEN** history is requested for a product whose latest price is the lowest in the window
- **THEN** the response carries the same verdict `DealVerdict` would assign

#### Scenario: An unknown product key is not an error
- **WHEN** history is requested for a key with no observations
- **THEN** the response is an empty series, not a 404 — an unwatched product is a data answer

### Requirement: Latest run report
`GET /v1/runs/latest` SHALL return the most recent daily run's report: flyers listed/selected/fetched/failed, items parsed/dropped, observations inserted/skipped, matches, alerts delivered, alerts suppressed broken down by reason, the alert audience where known, decode failure rate, and elapsed time.

#### Scenario: Suppression is reported by reason
- **WHEN** a run suppressed alerts for several different reasons
- **THEN** the response carries the per-reason breakdown, because a single total cannot distinguish a price ceiling that is too tight from an empty history from having already told you

#### Scenario: Delivered and suppressed reconcile against matches
- **WHEN** a run report is returned
- **THEN** delivered plus suppressed plus failures accounts for every match, so a shortfall is visible rather than silent

#### Scenario: An unknown audience is distinguishable from an empty one
- **WHEN** the sink could not report how many consumers are attached
- **THEN** the audience is reported as unknown rather than as zero

### Requirement: Per-watch health
`GET /v1/watches` SHALL return each watch with its terms, exclusion terms, alerting conditions, and, for the latest run, how many observations it matched, how many were suppressed by which reason, and how many alerted.

#### Scenario: A watch matching everything and alerting nothing is visible
- **WHEN** a watch matched many observations and every one was suppressed
- **THEN** the response shows the match count alongside the per-reason suppression counts

#### Scenario: Exclusion terms are shown
- **WHEN** a watch carries exclusion terms
- **THEN** they are returned, since they are the mechanism by which a watch stops matching the wrong thing

### Requirement: Alert history
`GET /v1/alerts` SHALL return alerts from the ledger in reverse chronological order: watch, product, merchant, the price alerted at, the verdict, and when it was sent.

#### Scenario: Alerts are traceable to the observation that caused them
- **WHEN** an alert is returned
- **THEN** it identifies the product key and merchant, so the price series behind it can be opened

### Requirement: Availability is honest about the database
The service SHALL report itself unhealthy when the database is unreachable, and SHALL NOT serve stale cached answers as though they were current.

#### Scenario: A database outage is visible
- **WHEN** PostgreSQL is unreachable
- **THEN** the health endpoint reports not-ready and reads fail rather than returning empty results that look like an absence of data
