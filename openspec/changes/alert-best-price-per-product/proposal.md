## Why

Today an alert is about a *listing*: one watch, one product at one merchant, in that merchant's flyer window. Butter at Metro and butter at IGA are two different `productKey`s, so they are two alerts, and comparing them is the reader's job. That is a consequence of a deliberate decision, recorded in `ProductKeys`:

> Cross-language and cross-merchant identity are explicitly NOT this key's job.

The Ariadne extraction removes that property. Ariadne owns identity resolution and issues canonical, cross-merchant `ProductId`s, so the two butters become one product. Dedup keys on the product key:

```scala
final case class AlertKey(watchId: WatchId, productKey: ProductKey, windowFrom: Instant, windowTo: Instant)
```

which means that on the day the id map is applied, the second merchant's price is suppressed as *"already alerted this window"* — **and it may be the cheaper one**. Nothing errors. The run report is clean. The suppression counter increments under a reason that reads as correct behaviour. You simply stop being told about a better price, and there is no signal anywhere that it happened.

The corpus rate understates the exposure. Measured on the 2026-08-26 run: 2,054 distinct product keys, 10 name-groups spanning more than one merchant, covering 28 keys — about 1.4%. But the products that merge are the staples every merchant stocks, which is exactly what a person puts on a watchlist. The live watchlist is butter, coffee and milk. The merge rate among *watched* products is far above the corpus rate, and the corpus rate is the number that looks reassuring.

So the change is not optional; only its direction is. Both the Demeter and Ariadne sessions recommended preserving today's behaviour by adding the store back into the key. **Calvin decided otherwise, on 2026-08-26: one alert, naming the best price.** This proposal exists so that outcome is designed rather than inherited — the same behaviour arrived at by accident would be indistinguishable from the regression described above.

## What Changes

- **Dedup becomes per product, not per listing.** The store dimension leaves the key deliberately, having been established as a decision rather than a side effect of the id-map rewrite.
- **The window leaves the key, and this is the part most likely to be missed.** `AlertDedup.keyOf` currently takes `validFrom`/`validTo` from the observation — the *flyer's* window — so removing only the store still yields two keys and two alerts for two merchants. The repair is not a better window: flyers are fetched incrementally, so a product's offers accumulate across runs within one week, and any window derived from them moves the key mid-week and re-alerts. The window becomes an expiry on the record instead, where nothing can move it by accident.
- **An alert names the price and the store offering it**, says what it beat, and names the runner-up's store too. Checkability is half the reason; the other half is that per-product alerting otherwise suppresses the offer a reader would actually have acted on in favour of a cheaper one at a chain they never visit.
- **A renewed flyer at an unchanged price no longer alerts**, replacing the rule that a new window is news again. The price instead carries `bestSince` — how long it has held — so the fact survives without the notification.
- **Improvement re-alerting extends across stores.** This half needs no new machinery: `AlertDedup.isNew` already re-fires when `now.cents < before.cents`, and `AlertRecord` already persists the price it last reported. Under a per-product key that naturally becomes "tell me when the best price improves, wherever it improved."

Not in scope: the Ariadne migration itself, the id map, and the dual-run. Those are Codex's, and are sequenced in `migration-demeter.md`. This change describes only what Demeter's alerting becomes once stable cross-merchant ids exist.

## Capabilities

### Modified Capabilities

- `alerting`: the dedup key, the prior-alert record, and the alert model. Dedup moves to `(watchId, productId)` with the validity window leaving the key entirely for the record to carry as an expiry (see `design.md` D2 — every candidate-derived window re-alerts). `Alert` gains the winning store, the runner-up and its store, and `bestSince`; both renders change, so the Home Assistant structured render is a consumer-visible change and not merely a wording one. A renewed flyer at an unchanged price stops being news.
- `price-history`: **not changed by this proposal, but changed underneath it, which is worth stating explicitly.** `PriceStats.rollingStats(key: ProductKey, …)` builds the baseline keyed on the product. Cross-merchant ids therefore blend every store into one distribution, which alters what "is this a good deal" means — a store that is habitually cheap begins to look permanently on sale, and a premium store's genuine sale correctly stops impressing. For a best-price alert that is arguably the more honest baseline, but it is a change to the core judgment and not merely to dedup, and it must be verified against real history rather than assumed.

## Impact

- **Blocked.** Demeter cannot implement this alone. It requires stable cross-merchant `ProductId`s, which is precisely the capability `ProductKeys` declines to provide. Every task here is gated on Ariadne being deployed and the id map produced. The proposal is written now, ahead of the work, because the alternative is that the decision survives only in cross-session message history and is rediscovered at cutover — by which time it has already happened.
- **Code**: `AlertDedup`, `Alert` and its two renders, and the selection step in `DailyRun.matchAndAlert` that currently emits one deal per matching observation.
- **Schema**: `alert_ledger` is keyed on `product_key` and stores one row per alerted listing. Under per-product dedup its grain changes, and the existing rows are per-merchant. Rewriting them through the id map is the same operation as the watch-key rewrite and should happen in the same step.
- **Consumers**: `demeter-insight` serves `GET /v1/alerts` from `alert_ledger` and the UI renders it. A change of grain is visible there and the alerts view will need to show the store.
- **Design questions: two answered on 2026-08-26, one open and non-blocking.** Ariadne confirmed its identity includes pack size, so "best" compares effective price like for like. The product-level window turned out not to exist — every candidate-derived window re-alerts — so it left the key. What remains open is whether the blended cross-store baseline distorts the verdict, and that is answered with data from Ariadne's dual-run rather than by discussion.
