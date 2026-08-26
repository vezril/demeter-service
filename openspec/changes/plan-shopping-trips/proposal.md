## Why

demeter answers "is this cheap?" It does not help you shop.

Those are different acts, and the gap between them is currently yours to bridge from memory. An alert is an event — it fires when something becomes a good deal, at whatever hour the run happens. A shopping trip is a decision made later, about one or two stores, covering everything worth buying at each. Nothing in the system serves that: `GET /v1/alerts` is a reverse-chronological log of past events, and the watches page reports per-watch health. Neither answers *"I am going out in ten minutes — which store, and what do I get there?"*

The one place the system already knows the answer is the daily run, which matched every watch against every current observation at 06:00 and then threw the detail away. Only aggregate counts survive: `matched: 270, suppressed: 270` with a per-reason map for the whole run. Which product, at which store, for which watch, is not recorded anywhere. The `add-insight-service` watches view says so in the UI, in as many words:

> Never alerted. Why it stayed silent is not recorded yet — the run report counts suppression across the whole run, not per watch.

So this change is mostly about keeping what a run already computed, and then projecting it two ways: by watch (which closes that gap) and by store (which is the trip).

## What Changes

- **Persist per-match results.** Each run records what it matched: watch, product, merchant, price, verdict, and the decision — alerted, or suppressed with its reason. This is the enabling change; both views below are reads over it, and neither re-evaluates. Re-deriving deals at read time would let the trip view and the alerts disagree about the same day, which is precisely the class of failure this project keeps finding.
- **A trip view in demeter-ui**, grouping the current run's qualifying deals **by store**, so the stores can be compared and one chosen. Comparison is the whole point: you visit one or two, and the question is which.
- **Export the chosen store's list to Apple Reminders**, via a Shortcut on the device. One Reminders list per store.
- **Items previously exported are marked** with the date they were last sent, rather than hidden or re-presented as new.
- **The trip endpoint is served by `demeter-ui`, not `demeter-insight`.** Insight has no Ingress and is reachable only inside the cluster; that was deliberate, because its write routes are guarded by a database role rather than by authentication. A Shortcut must reach *something*, and the UI already owns the `demeter.tailscale` ingress.

## Capabilities

### New Capabilities

- `trip-planning`: current qualifying deals projected by store; a chosen store's list exported to Apple Reminders without any credential leaving the device; and a record of what was exported, so a repeat appearance can be labelled rather than silently repeated.

### Modified Capabilities

- `persistence`: adds match results — one row per (run, watch, product, merchant) with price, verdict and outcome — and a small record of exported items. The first is the change that makes both this and the unresolved per-watch reporting possible.
- `orchestration`: the daily run writes its match results. As with the run report, a write failure is warned about and never raised: the run already happened, and losing bookkeeping is a smaller loss than failing a fetch that cannot be repeated.
- `insight-api`: adds a read over match results, projected by store and by watch.
- `insight-ui`: adds the trip view and the export action.

## Impact

- **Not blocked.** Unlike `alert-best-price-per-product`, this needs nothing from Ariadne. Grouping by store is served exactly by today's merchant-scoped `productKey` — a key that is already per-store is the right shape for a per-store view. Cross-merchant ids would *add* something later (a true "this is $1 cheaper at IGA" comparison for one product), but nothing here waits on them.
- **Complementary to `alert-best-price-per-product`, not dependent on it.** That change collapses stores into one alert naming the cheapest. This one fans the same data back out by store. They are two projections of one dataset, and the per-store detail (b) drops from the *alert* is exactly what the trip view exists to preserve. Worth stating plainly because the two sound contradictory and are not.
- **Credential posture unchanged.** No iCloud credential is introduced. Apple Reminders has no server API and its CalDAV path needs an app-specific password that iCloud does not scope to Reminders — unlike `demeter_watch`, whose blast radius PostgreSQL itself enforces. Running the export on-device keeps every credential in this system constrained by the thing holding it. The cost is that demeter cannot see items being ticked off in the shop, so an exported item is marked *"was on your list"* rather than *"you bought this"*. That is the honest statement of what demeter can know, and it was chosen over the alternative.
- **Schema**: two new tables. The match-results table is the larger one — bounded by matches per run, which was 270 on 2026-08-26 — and wants a retention policy from the outset rather than after it becomes a problem.
- **Write path**: recording an export is a write, and `demeter_watch` is granted `INSERT, UPDATE, DELETE` on `watch_item` and nothing else. It needs one further narrowly-scoped grant. `price_observation`, `raw_response` and `alert_ledger` stay unwritable, for the same reason as always: flyers expire and that data cannot be re-fetched.
- **Prerequisite outside this repo**: the tailnet currently has two nodes, this Mac and `mimir`. No iPhone. A Shortcut on the phone cannot reach `demeter.tailscale` until the phone joins the tailnet. It is a two-minute change and it is not really this feature's — a grocery tool being reachable from a phone is worth having regardless — but without it the export runs only on the Mac, and planning a trip at a desk is most of the convenience gone.
