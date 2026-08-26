# Tasks

Not blocked on Ariadne. Everything here works against today's merchant-scoped `productKey`, which is already the right shape for a per-store view.

Phase 1 is independently useful and answers a question the UI currently admits it cannot: the watches view says *"Why it stayed silent is not recorded yet."* Phase 2 is the trip itself. Phase 3 leaves the repo.

## 0. Decide before building

- [x] 0.1 ~~Q1 — what qualifies for a trip?~~ **Answered 2026-08-26 (Calvin): whatever is currently alertable.** A trip is push-button, list on the phone, tick off in the shop; anything not ticked off stays in alerts. Exporting is therefore not a decision about the deal (D7).
- [x] 0.2 ~~Q2 — the trip's own window.~~ **Answered 2026-08-26: `validFrom <= now <= validTo`.** Not-yet-started deals are excluded too — a price that is not live yet is only discoverable at the shelf.
- [x] 0.3 ~~Q3 — where the export record lives.~~ **Answered 2026-08-26: a table, `INSERT` + `SELECT` only.** The browser would need no grant but the record would be per-device — planned on the Mac, invisible on the phone, which is exactly where the label has to appear.
- [x] 0.4 ~~Retention policy for match results.~~ **Answered 2026-08-26: 90 days.** The verdict history window is 56 days, so nothing older can affect a current judgment; 90 leaves margin. Safe to expire because they are derived — unlike observations, which cannot be re-fetched.

## 1. Persist match results — the enabling change

- [ ] 1.1 Table: one row per (run, watch, product, merchant) with price, verdict, outcome, and suppression reason where suppressed.
- [ ] 1.2 The daily run writes them. A write failure warns and never raises — the run already happened, and the fetch cannot be repeated. Same rule as the run report.
- [ ] 1.3 Assert the persisted rows reconcile with the run report's aggregates: alerted plus suppressed equals matched, per reason. Two records of one run that disagree is worse than one record.
- [ ] 1.4 Retention per 0.4.
- [ ] 1.5 `insight-api` read, projected by watch. **Closes the existing gap** — the watches view can finally say why a watch stayed silent.
- [ ] 1.6 UI: replace the "not recorded yet" placeholder on the watches page with the real per-watch reasons.

## 2. The trip

- [ ] 2.1 `insight-api` read, projected by merchant, per 0.1 and 0.2.
- [ ] 2.2 Trip view in demeter-ui: stores side by side with counts and value, chosen after comparing.
- [ ] 2.3 Every row carries its run and `validTo`; the view states how old the run is (R2). A trip planned Wednesday evening must not present Tuesday's world as current.
- [ ] 2.4 Export endpoint on **demeter-ui**, not insight (D6). One merchant per export.
- [ ] 2.5 Record the export; mark repeats with the date last sent.
- [ ] 2.6 The label says *was on your list*, never *you bought this*. Worth its own test: it is a one-word edit away from claiming something demeter cannot observe.
- [ ] 2.7 Exporting writes no alerting state (D7). Test that an exported, unbought item still alerts in the next window, and that no `alert_ledger` row moves on export.
- [ ] 2.8 Consider a horizon past which the label is dropped rather than shown (R4) — an item last sent in June, resurfacing in September, is odd rather than helpful.

## 3. The device half

- [ ] 3.1 **Join the iPhone to the tailnet.** Not this feature's work, but this feature does not reach a phone without it — the tailnet is currently this Mac and `mimir`. Until then the export runs only on the Mac, and planning a trip at a desk is most of the convenience gone.
- [ ] 3.2 A Shortcut that fetches the chosen list and creates the reminders on-device.
- [ ] 3.3 One Reminders list per store, of the Groceries type, so iOS sorts by department inside it (D2).
- [ ] 3.4 Treat the payload as a public contract: boring, additive, versioned in spirit. The Shortcut lives outside the repo, cannot be migrated, and drifts silently (R3).
- [ ] 3.5 Verify end to end on a real phone in a real shop. The aisle is where a checklist that reads badly becomes obvious, and no amount of looking at it in a browser substitutes.

## Not doing

- Continuous sync, and the deletion-tracking, curation and iCloud credential it would require. Trip-triggering removes those problems rather than solving them.
- Reading Reminders back. The cost of that choice is the wording in 2.6.
- Cheapest-basket optimisation. This groups so a person can choose; it does not choose.
- A "got it" action in demeter-ui. It is the credential-free way to express *I bought this*, and it is the escape hatch if D7's cost ever grates — but it means ticking every item off twice, in Reminders and again here, which is a worse daily cost than an occasional unwanted repeat.
