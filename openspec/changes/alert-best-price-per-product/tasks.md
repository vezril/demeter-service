# Tasks

**Every task is blocked on Ariadne.** Demeter cannot start this: it needs stable cross-merchant `ProductId`s, which is exactly the capability `ProductKeys` declines to provide. The list exists so the work is scoped and the gates are visible at cutover, not so it can be picked up now.

Sequenced against `codex/apps/ariadne` → `migration-demeter.md`. Steps 1–3 of that plan (deploy, backfill, dual-run) are Codex's and are not repeated here.

## 0. Gates — before any code

- [ ] 0.1 Ariadne deployed and the Demeter-id → `ProductId` map produced. *(Codex; blocks everything)*
- [x] 0.2 ~~Answer Q2: what is `productWindow`?~~ **Answered 2026-08-26: there is no product window.** Every candidate-derived window moves the key mid-week and re-alerts. The window leaves the key; the record carries `expiresAt`. *(ours)*
- [x] 0.3 ~~Answer Q1: does the resolver merge pack sizes?~~ **Answered 2026-08-26: no.** Identity includes size, so best price is lowest `effectivePrice`. *(Ariadne)*
- [ ] 0.4 Capture the pre-cutover alert stream for one full flyer week, to compare against. Without a baseline, R3 leaves no way to tell the change working from the change broken — alert volume falls either way.

## 1. Dedup

- [ ] 1.1 `AlertKey` becomes `(watchId, productId)`. `AlertRecord` gains `expiresAt`, `winningStore` and `bestSince`; `isNew` gains `now`.
- [ ] 1.2 Test that two observations of one product with **different flyer windows** produce one key. Load-bearing: a test that merely asserts the key has no store field passes against the broken version.
- [ ] 1.3 Test that an offer arriving on a *later run* does not re-alert an unchanged product. This is the failure a candidate-derived window would have caused, and it is the one that cannot be caught by inspecting the key.
- [ ] 1.4 Expiry: past `expiresAt` is news again; an improvement on a longer-running flyer extends it.
- [ ] 1.5 Confirm improvement re-alerting still holds across merchants and that a rise does not. `alertedPrice` is already persisted; this is a scenario, not an implementation.
- [ ] 1.6 Rehydration reads records with their expiry rather than filtering by flyer window.

## 2. Best-price selection

- [ ] 2.1 Pure selection over a product's candidate observations: winner plus beaten set, lowest `effectivePrice` wins.
- [ ] 2.2 A priceless observation never wins against a priced one. Unknown is not free — the same rule as `alertAudience`.
- [ ] 2.3 Property test: no member of the beaten set is cheaper than the winner. Cheap, and it is the invariant that makes a silently wrong comparison loud.
- [ ] 2.4 Wire selection into `DailyRun.matchAndAlert`, which today emits one deal per matching observation.

## 3. Alert model

- [ ] 3.1 `Alert` carries the winning merchant, the count considered, the next-best price **and its merchant**, and `bestSince`.
- [ ] 3.2 `renderPlain` names the store, the runner-up and its store, and how long the price has held.
- [ ] 3.3 `renderStructured` gains the same. **Consumer-visible** — the Home Assistant sink's payload changes shape.
- [ ] 3.4 A sole offer renders as a sole offer, not as a won comparison.

## 3b. Unchanged prices

- [ ] 3b.1 An expired record renewed at the same or a worse price does not alert; the expiry advances.
- [ ] 3b.2 `bestSince` is carried forward on a non-change **and moves on a change**. Both directions — a frozen `bestSince` reads as merely old, not as wrong (R5).
- [ ] 3b.3 An improvement reports the previous price and how long it stood.
- [ ] 3b.4 `demeter-insight` and the UI show `bestSince` wherever a price appears, so a continuing deal remains visible after it stops alerting.

## 4. Suppression accounting

- [ ] 4.1 Losing offers are excluded from `suppressedByReason` while still counting as matches.
- [ ] 4.2 Assert the run report's reconciliation still balances: `matched == delivered + suppressed + unaccounted`. Changing what counts as a suppression is exactly how that identity gets quietly broken, and `unaccounted` is the field that catches it.

## 5. Ledger migration

- [ ] 5.1 Rewrite `alert_ledger` rows through the id map, in the **same step** as the `watch_item` key rewrite. Split them and dedup reads a ledger keyed one way while writing it another, which re-alerts everything once — indistinguishable from the regression this whole change exists to prevent.
- [ ] 5.2 `demeter-insight`'s `GET /v1/alerts` and the UI alerts view show the winning store.

## 6. Verification — the part that is not optional

- [ ] 6.1 Compare one full cycle against the 0.4 baseline on the same input. **Alert volume falling is the intended effect and also what a bug looks like**, so volume alone proves nothing; compare which products alerted, not how many.
- [ ] 6.2 Verdict distribution pre/post (R2). Cross-merchant ids blend stores into one baseline, which changes the judgment underneath this change without touching any of its code.
- [ ] 6.3 Keep the id map until 6.1 and 6.2 have passed over a full flyer week. It is the only thing that makes step 4 reversible.

## 7. Separately, and not blocked by any of this

- [ ] 7.1 Expose `merchantIds` in the watch form. The API accepts it, `WatchItem.inScope` honours it, no live watch uses it, and the form has no field for it. Considered as a prerequisite of this change and rejected — naming the runner-up (D6) removes the dependency — but 96 distinct merchants have been observed and "stop showing me that chain" is worth having on its own.
