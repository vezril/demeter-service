## Context

Demeter's alerting has always been per listing, because its product key is merchant-scoped by construction. Ariadne's extraction introduces canonical cross-merchant `ProductId`s, and `AlertKey` keys on the product. The behaviour therefore changes on the day the id map is applied, whether or not anyone designs it.

Two shapes were on the table:

| | (a) preserve today | (b) best price — **chosen** |
|---|---|---|
| Key | `(watchId, productId, storeId, window)` | `(watchId, productId)` + expiry on the record |
| Same butter, two stores | two alerts, reader compares | one alert, naming the cheaper |
| Cost | ~nothing; `PriceObserved` carries `storeId` | a real design, this document |
| Risk | none — it is the status quo | the comparison must be right, and must stay right |

Both sessions recommended (a). Calvin chose (b) on 2026-08-26. (b) is the better product — the system doing the comparison is the whole point of it holding the price history — but it moves work from the reader into the code, and code that silently compares the wrong two things is worse than a reader comparing two alerts.

## Goals / Non-Goals

**Goals**

- One alert per watched product while a deal stands, naming the best price and where it is.
- The comparison is legible: an alert says what it beat, so a wrong answer is visible rather than merely quiet.
- Re-alert when the best price improves, wherever it improved.
- The transition from per-listing to per-product is a deliberate, verified step, not an emergent property of the id map.

**Non-Goals**

- Cross-merchant identity. Ariadne's, and the reason this is blocked.
- The migration sequence, id map, and dual-run. Codex's, in `migration-demeter.md`.
- Multi-store trip planning ("cheapest basket across stores"). A different and much larger problem; this change alerts per product, not per shop.
- **Cross-size comparison** ("cheapest butter, any size"). Ariadne's identity includes size (Q1), so this is not something the migration hands us. It would be a watch over a *set* of products compared on unit price — a separate design, and one that has to confront `unit_cents` being nullable for unparseable sizes.
- Changing what counts as a deal. `DealVerdict` and the suppression rules are untouched here — see the baseline question below, which touches them from underneath.

## Decisions

**D1. Dedup keys on `(watchId, productId)`.** The store leaves the key — that is the decision itself — and so does the window, for the reason in D2.

**D2. The window leaves the key entirely. The record carries an expiry instead.**

This began as "the window must become product-level", and that framing was wrong. Every product-level window we could construct is self-defeating. Recording the reasoning, because the wrong answer looks correct.

Dedup remembers what it already said by filing a record under a key:

```scala
def keyOf(deal: Deal) =
  AlertKey(deal.watch.id, deal.observation.productKey,
           deal.observation.validFrom, deal.observation.validTo)  // the FLYER's window
```

Removing `storeId` while leaving that window is the obvious near-miss: merchants run different flyer weeks, so the same product at two stores still yields two keys and two alerts. It would look implemented and behave exactly as before.

But the repair — derive a window for the *product* from its offers — fails worse:

```
Jul 23   only Metro fetched.   window = Jul23-Jul30 -> key K1 -> alert, file under K1
Jul 25   IGA's flyer arrives.  window = Jul23-Aug01 -> key K2 -> nothing under K2 -> ALERT AGAIN
                                        ^^^^^^^^^^^ the Jul 23 record is still there,
                                                    under K1, and now unreachable
```

Not hypothetical. Flyer selection is ledger-based, so demeter fetches incrementally — 18 of 164 listed on 2026-08-26 — and other merchants' flyers arrive on later days *within the same flyer week*. A product's candidate set genuinely grows mid-week. Intersection moves, union moves, and "the winning offer's window" moves the instant someone cheaper appears.

A key containing anything derived from the candidate set cannot be stable, and an unstable key is indistinguishable from no memory at all.

So the window comes out of the key:

```
key     = (watchId, productId)                            stable by construction
record  = { bestCents, winningStore, expiresAt, bestSince }
```

`expiresAt` is the winning observation's `validTo`: the alert stands until the deal it actually named ends, which is truer than a flyer boundary and is *recorded state*, so no incoming observation can move it by accident. An improvement updates it deliberately — so a better price on a longer-running flyer correctly extends the alert's life.

Dedup stays pure; prior state is passed in exactly as `isNew` already takes it. It gains `now`, which `DailyRun` already has.

**D3. An alert states its comparison.** It carries the winning store, the winning price, and the count and best of what it beat. A best-price claim that cannot be checked is an assertion; the reader has no way to tell a correct comparison from a comparison over one observation that happened to be the only one seen.

**D4. Improvement re-alerting is inherited, not built.** `AlertDedup.isNew` re-fires on `now.cents < before.cents` and `AlertRecord` persists `alertedPrice`. Under a per-product key this already means "the best price got better." No new mechanism; it does need a scenario proving it now spans stores.

**D5. A losing store is not a suppression.** The suppression counters exist to explain silence to a person tuning a watch, and `suppressedByReason` is surfaced in the run report and the UI. Counting the four stores that lost a comparison as four suppressions would inflate that map with events that are not silence — the product *was* alerted. Losers are recorded on the alert as what it beat (D3), not in the suppression map.

**D6. The alert names the runner-up and its store.** Not only for auditability (D3) but because it is what keeps (b) usable.

Under (a) a reader saw every offer and filtered mentally: *Provigo is cheaper but I am going to Metro anyway.* (b) suppresses the offer the reader would actually have acted on, and names one at a store they may never visit. That is a genuine regression, and it is invisible — the alert looks correct, because it is correct.

`WatchItem.inScope` already allows a watch to be limited to chosen merchants, and the obvious answer is "scope your watches". It was briefly considered a prerequisite of this change and **rejected**: zero of the three live watches use scoping, the watch form does not expose `merchantIds` at all, and 96 distinct merchants have been observed. A design that only behaves well once configured ships broken and stays broken until someone does data entry.

Naming the runner-up restores the lost information with no configuration at all:

```
Butter — $3.99 at Provigo   ·  best of 4 offers
                               next: $5.29 at Metro
```

Merchant scoping remains a real refinement — "stop showing me Provigo" — but it is a convenience, not load-bearing. It is worth exposing in the watch form on its own merits, separately from this change.

**D7. A renewed flyer at an unchanged price does not alert, but the price carries how long it has held.**

This replaces the existing rule that a new flyer window is news again. Re-announcing an unchanged price every week is noise; the previous rule only looked reasonable because the window was in the key and made it automatic.

Silence alone would be wrong though. The fact must survive, so the record carries `bestSince` — when the current best price was first reported — set when the price changes and carried forward when it does not. Anything that shows the price shows the date:

```
alert, price improved:  $3.99 at Provigo — was $4.99 since Jul 23
no alert, unchanged:    butter · still $4.99 at Metro · since Jul 23
```

`bestSince` is close to the question underneath the whole system: a price that has sat at $4.99 for two months is not a sale, whatever the flyer calls it. It is a cheap approximation of `DealVerdict`, placed where a person actually reads it.

Honest limitation: `bestSince` is *since demeter first reported it*, not since the price truly began. The true answer is derivable from `price_observation`, which holds the full series, and could replace this later without changing the contract. It is deliberately not derived now — a per-alert history query buys precision nobody has asked for.

## Risks / Trade-offs

**R1. A wrong comparison is silent.** The failure mode this change is meant to prevent is also its own worst failure: if "best" picks the wrong observation, the reader is told a price and a store, believes it, and there is no second alert to contradict it. D3 is the mitigation — an alert that shows what it beat can be audited. Consider also asserting the invariant directly: no beaten price may be lower than the winning price.

**R2. The baseline changes underneath the verdict.** `PriceStats.rollingStats` is keyed on the product, so cross-merchant ids blend stores into one distribution. A habitually cheap store starts looking permanently on sale; a premium store's real sale correctly stops impressing. Arguably more honest for a best-price product, but it is a change to the judgment, not the dedup, and it arrives silently with the id map. **Verify during Ariadne's dual-run by comparing verdict distributions pre- and post-, alongside the product-key cardinality check.**

**R3. Fewer alerts is the intended effect and also what a bug looks like.** Alert volume falls by design. That makes volume useless as a health signal for this change, which is precisely how the 2026-08-26 flyer loss stayed invisible. Do not use "alerts still arriving" as evidence it works; compare against the pre-cutover stream on the same input.

**R4. Grain change in `alert_ledger`.** Existing rows are per-merchant. They must be rewritten through the id map in the same step as the watch keys, or dedup after cutover reads a ledger keyed one way and writes it another — which re-alerts everything once, looking like the very regression this design exists to avoid.

**R5. `bestSince` can silently freeze.** It is carried forward on every non-change, so a bug that fails to update it on a price change produces a date that is merely old rather than obviously wrong. Nothing about the alert would look off. Worth a scenario asserting it moves when the price moves, not only that it persists when the price holds.

## Migration Plan

Entirely downstream of Ariadne. Sequenced against `migration-demeter.md`:

1. Ariadne deployed, backfill complete, id map produced. *(Codex)*
2. Dual-run. Add to its comparison: verdict distribution, and product-key cardinality. *(R2, R3)*
3. Rewrite `watch_item` keys and `alert_ledger` rows through the id map, together. *(R4)*
4. Cut over dedup and the alert model in one release. The window change (D2) ships with it; shipping the key change without it is the silent no-op.
5. Compare one full cycle of alerts against the pre-cutover stream before disabling the old path.

Rollback: the old key is derivable while the id map is retained, so step 4 is reversible for as long as the map exists. Keep it until alert volume has been compared over at least one full flyer week.

## Open Questions

**Q1. Does Ariadne's resolver merge across pack sizes?** — **ANSWERED, 2026-08-26 (Ariadne, via Codex): no.**

Product identity includes size. Ariadne is GTIN-keyed, 454 g and 250 g carry different GTINs, and its matcher uses size as a strong discriminator. A `ProductId` therefore implies one size.

So **best = lowest `effective_cents`**, like for like, and the comparison needs no unit-price normalization. This is the answer that makes D3 simple: two prices for the same `ProductId` are directly comparable, and an alert claiming one beat the other is checkable by arithmetic.

It also disposes of a subtlety that would otherwise have needed a rule: `unit_cents` is nullable, because a product whose size will not parse has no unit price. Had sizes merged, every comparison would have needed a policy for observations that cannot be normalized — with size in the identity, that case cannot arise within a single product.

Consequence worth recording: "cheapest butter regardless of size" is now definitively **not** something the id map gives us. It would be a separate Demeter feature — a watch over a set of products, compared on unit price — and it is a different design from this one. Listed under Non-Goals.

**Q2. What is the product-level window?** — **ANSWERED, 2026-08-26: there isn't one.**

The question was wrong. Intersection, union and the winner's window are all functions of the candidate set, which grows mid-week as flyers are fetched — so all three move the key while the product stays the same, and re-alert. See D2. The window leaves the key and becomes `expiresAt` on the record, which only changes when something deliberately updates it.

**Q3. Is the blended baseline what we want?** *(non-blocking; decide with data from R2)*

If blending proves to distort verdicts, the fix is to keep the baseline per `(product, store)` while alerting per product — compare each store's price against its own history, then pick the best across stores. That is more faithful and more code. Defer until the dual-run says whether it is needed.
