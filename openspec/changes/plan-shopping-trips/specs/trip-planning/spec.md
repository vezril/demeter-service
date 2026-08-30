# Trip Planning

The current run's decisions, projected by store, so one store can be chosen and its list carried into the shop. Reads decisions; never makes them. Exports without any credential leaving the device.

## ADDED Requirements

### Requirement: Trips read persisted decisions, never re-evaluate

The trip view SHALL be built from match results recorded by the run that produced them, and SHALL NOT re-run matching, scoring or the deal decision at read time.

Two evaluations of one day can disagree — a watch edited since the run, a different clock, a second code path — and the disagreement is silent: a deal appears in the trip view that no alert mentioned, or is missing from it, and both readings look correct. The same rule already governs statistics in `insight-api`, where `PriceStats` and `DealVerdict` are reused rather than reimplemented so the maths cannot drift between services. This extends it from numbers to decisions.

#### Scenario: The trip agrees with what was alerted
- **WHEN** the trip view is built for the run that produced N alerts
- **THEN** every alerted product appears in the trip view under the merchant it was alerted for

#### Scenario: Editing a watch does not retroactively change a past trip
- **GIVEN** a run that matched a product under a watch
- **WHEN** the watch is edited so the product would no longer match
- **THEN** the trip view for that run is unchanged
- **AND** the change takes effect on the next run, as watchlist changes always do

### Requirement: Deals are grouped by store

The trip view SHALL group qualifying deals by merchant, and SHALL report per merchant enough to choose between them: how many items qualify and what they are worth. A merchant with no qualifying deals SHALL NOT be listed.

Grouping by store is the purpose rather than a presentation choice: a trip visits one or two shops, so the question being answered is which.

"Merchant" here means the **chain**. Today that is what `merchant_id` is, so the requirement is unambiguous as written; after the Ariadne migration a Store is an individual franchise and a flyer price is a `Regional(chain, area)` fact, at which point grouping SHALL remain at the chain for flyer-derived facts. Franchises of one chain in one region share a flyer, so their prices are identical by construction, and grouping below the chain would present a modelling artefact as a choice between shops.

#### Scenario: Grouping does not descend below the chain for flyer-derived facts
- **WHEN** several franchises of one chain in one region carry the same flyer price
- **THEN** they appear as one row, not one row per franchise

#### Scenario: Stores are comparable at a glance
- **WHEN** a run has qualifying deals at three merchants
- **THEN** the view lists exactly those three, each with its item count

#### Scenario: A merchant with nothing worth a detour is absent
- **WHEN** a merchant has observations but none qualifying
- **THEN** it does not appear in the trip view

### Requirement: Every row carries its provenance and expiry

Each row SHALL carry the run it came from and the deal's `validTo`, and the view SHALL show when the underlying run happened.

The view is as old as the last run, which may be hours or — late in a flyer week — a day or more. Presenting a stale decision as the current state is the failure this project has repeatedly found in other forms, and it is worse here because the reader acts on it by driving somewhere.

#### Scenario: A trip states how old it is
- **WHEN** the trip view is shown
- **THEN** it reports the time of the run whose decisions it displays

#### Scenario: An expired deal is not offered for a trip
- **WHEN** a qualifying deal's `validTo` is before now
- **THEN** it is excluded from the trip view

#### Scenario: A deal that has not started is not offered either
- **WHEN** a qualifying deal's `validFrom` is after now
- **THEN** it is excluded from the trip view
- **AND** this is deliberate: sending someone to a shelf for a price that is not live yet is only discoverable at the shelf

### Requirement: Export is per store and credential-free

Exporting SHALL send one chosen merchant's items, SHALL be performed on the user's device, and SHALL NOT require any credential for Apple services to be held by demeter or its deployment.

One merchant per export, because an iOS Groceries list auto-sorts by department, which occupies the same axis as store; store wins, since only one shop can be visited at a time, and each exported list then gets aisle-sorting for free. Exporting every merchant would create one list per merchant — 15 were observed in a single day.

The export payload SHALL be served by `demeter-ui`. `demeter-insight` has no Ingress and is reachable only inside the cluster, which is deliberate: its write routes are guarded by a database role rather than by authentication.

#### Scenario: One store's list is exported
- **WHEN** a merchant is chosen and exported
- **THEN** the payload contains that merchant's qualifying items and no others

#### Scenario: No Apple credential is held by the deployment
- **WHEN** the deployment's configuration and secrets are enumerated
- **THEN** none contains a credential for an Apple service

#### Scenario: The export payload is not served by the reader
- **WHEN** the export endpoint is requested
- **THEN** it is served by demeter-ui
- **AND** demeter-insight remains without an Ingress

### Requirement: A previously exported item is marked, not hidden or repeated as new

An item that qualifies again having been exported before SHALL be shown, and SHALL carry the date it was last exported. It SHALL NOT be omitted, and SHALL NOT be presented as though it had not been sent before.

The label SHALL state what demeter knows — that the item was on a previous list — and SHALL NOT assert that it was bought. Ticking an item off happens in the shop, and demeter does not read Reminders back; claiming a purchase it cannot observe would be a stronger statement than the evidence supports.

#### Scenario: A repeat is labelled with when it was last sent
- **GIVEN** butter was exported on Aug 25
- **WHEN** it qualifies again on Sep 01
- **THEN** it appears in the trip view marked as last sent on Aug 25

#### Scenario: A repeat is not hidden
- **WHEN** an item that was exported before qualifies again
- **THEN** it is present in the view

#### Scenario: The label does not claim a purchase
- **WHEN** a previously exported item is displayed
- **THEN** the label refers to it having been on a list
- **AND** does not state or imply that it was bought

### Requirement: Exporting changes nothing about the deal

Exporting SHALL NOT suppress, dismiss, acknowledge or otherwise alter any alerting state. An item that was exported and not bought SHALL continue to be alertable exactly as if it had never been exported.

Pressing a button is not a purchase. demeter cannot observe items being ticked off in the shop — that is the accepted cost of holding no Apple credential — so treating an export as evidence of buying would infer a fact the system has no access to, and would silently stop telling someone about a deal they never acted on.

Repetition within a flyer window is already handled by alert dedup, which suppresses on "already alerted this window"; this requirement is about not adding a second, weaker suppression on top of it.

#### Scenario: An exported item still alerts
- **GIVEN** an item exported to a trip list
- **WHEN** the conditions that made it alertable still hold in a later window
- **THEN** it is alerted as it would have been without the export

#### Scenario: Export does not write alerting state
- **WHEN** an export is performed
- **THEN** no row in `alert_ledger` is created, altered or removed

### Requirement: Export records are append-only

A record of an export SHALL be written when the export is performed, and the role writing it SHALL hold `INSERT` and `SELECT` on that record and nothing further.

An export is a fact about something that already happened; it is not editable after the event. The write role stays confined for the reason it was confined originally — `price_observation`, `raw_response` and `alert_ledger` cannot be rebuilt, because flyers expire.

#### Scenario: The export role cannot reach the price history
- **WHEN** the role writing export records attempts any write to `price_observation`, `raw_response` or `alert_ledger`
- **THEN** PostgreSQL refuses it on the role's privileges

#### Scenario: Export records cannot be altered after the fact
- **WHEN** the role attempts to UPDATE or DELETE an export record
- **THEN** PostgreSQL refuses it
