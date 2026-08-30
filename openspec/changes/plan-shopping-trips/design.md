## Context

Four decisions were taken in conversation on 2026-08-26 before anything was designed, and they removed most of the difficulty. Recording them with their reasoning, because three of them are the *narrow* choice and a later reader will otherwise wonder why the obvious richer version was not built.

| | chosen | rejected |
|---|---|---|
| Trigger | you press something when heading out | continuous sync each morning |
| Grouping | by store, chosen after comparing | one flat list |
| Credential | none — export runs on-device | iCloud app-specific password in the cluster |
| Repeat items | "was on your list Aug 25" | "you checked this off Aug 25" |

The first is the load-bearing one. Continuous sync would have required demeter to respect deletions (or re-add every morning what you threw away), to curate hard enough that nine butters did not arrive at once, and to hold an iCloud credential. Trip-triggering deletes all three problems rather than solving them: nothing auto-adds, so nothing can fight you.

## Goals / Non-Goals

**Goals**

- Answer "which store, and what do I get there" in one view, at the moment of deciding.
- Get the chosen store's list into the hand that will be holding it in the shop.
- Never present a repeat as news, and never silently drop it either.
- Keep the numbers identical to what alerting decided that morning.

**Non-Goals**

- Continuous sync, and everything downstream of it.
- Reading Reminders back. Deliberate, and it is what "was on your list" costs.
- Cheapest-basket optimisation across stores. This groups by store so a person can choose; it does not choose.
- Quantities, staples, or anything resembling a real grocery list. This lists *deals worth a detour*, not what is in the fridge.
- Changing what counts as a deal. The trip view reads decisions; it does not make them.

## Decisions

**D1. Read persisted match results; never re-evaluate.**

The trip view could re-run matching over current observations at read time. It must not. Two evaluations of the same day can disagree — different code paths, different clocks, a watch edited in between — and the failure is silent: the trip view shows a deal the alert never mentioned, or omits one it did, and both look entirely plausible. `add-insight-service` already made this call for statistics ("Reuses `PriceStats` and `DealVerdict` rather than reimplementing them, so the maths cannot drift between the two services"); this is the same rule applied to decisions rather than numbers.

It also closes an existing gap rather than adding a parallel one. The watches view currently admits it cannot say why a watch stayed silent, because suppression is counted per run and not per watch. Persisting match results answers that question and this one with the same table.

**D2. Store is the grouping dimension, and therefore the Reminders *list* dimension.**

An iOS 17 Groceries list auto-sorts its items by department — produce, dairy, bakery — which is genuinely useful and entirely free. But that occupies the same axis as store: one list cannot be grouped by store *and* auto-sorted by aisle. Store wins, because you can only be in one shop at a time. Each exported list is per-store and gets aisle sorting inside it.

This is also why the export happens after choosing. Exporting every store would create a Reminders list per merchant — 15 were seen on 2026-08-26 and 96 all-time — which is not a feature.

**D3. The UI compares; Reminders executes.**

Deciding wants a table: stores side by side, counts, what you would save. Shopping wants a checklist in your hand. Reminders is bad at the first and good at the second, and the browser is the reverse. So the comparison stays in demeter-ui and only the chosen list crosses over.

**D4. The export is on-device and credential-free.**

Apple has no server API for Reminders. The CalDAV path works — verified as far as `Basic realm="MMCalDav"` on 2026-08-26 — but needs an app-specific password, and iCloud does not scope such a password to Reminders; it authenticates to the account. Every other credential in this system is constrained by whatever holds it, most sharply `demeter_watch`, which PostgreSQL confines to one table. An account-wide iCloud credential in a k8s secret would be the first exception, and it would be the broadest thing in the cluster.

So the export is a Shortcut on the device: it fetches the chosen list and creates the reminders locally. Nothing authenticates to iCloud but the phone that is already signed into it.

The cost is stated plainly in D5.

**D5. A repeat says "was on your list", not "you bought it".**

Ticking an item off happens on the phone, in the shop. Without reading Reminders back — which D4 forgoes — demeter cannot observe it. It knows only what it sent and when.

So an item that qualifies again is shown, and labelled with the date it was last exported. Not hidden: you may well want more butter. Not presented as new: you have seen it. The weaker sentence is the true one, and a system that says "you bought this" when it means "I mentioned this" is worse than one that says less.

This is the same shape as `bestSince` in `alert-best-price-per-product`: carry the history and let the reader judge, rather than collapsing it to a binary that has to be right.

**D6. The trip endpoint is served by demeter-ui.**

`demeter-insight` has no Ingress and is reachable only inside the cluster. That is deliberate — since 0.6.0 its write routes are guarded by a database role rather than by authentication, so the less exposed it is, the better. A Shortcut has to reach something over the tailnet, and demeter-ui already owns `demeter.tailscale`. Exposing insight to give a Shortcut a shorter path would undo a decision taken one day earlier for good reasons.

**D7. Exporting is not evidence of buying, and must not suppress anything.**

The lifecycle is: press the button, the list appears on the phone, items get ticked off in the shop. The natural corollary — *ticked off should stop appearing* — is the one thing D4 gives up, because demeter never sees the tick.

What it costs is smaller than it sounds and larger than nothing:

```
within a flyer window   dedup already suppresses ("already alerted this window").
                        Buying it on Thursday and being quiet on Friday is
                        existing behaviour. Covered.

across windows          the deal returns next week, alerts again, and reappears
                        in the trip view. Having bought a month's supply has no
                        expression in the system; the only lever is pausing the
                        watch.
```

So exporting SHALL NOT change any alerting state. Pressing a button is not a purchase, and a system that dropped exported items from alerts would be inferring one from the other. The label from D5 is the whole mechanism: it is a hint, not a state, and the judgment stays with the reader.

If this ever grates there is a credential-free answer that needs no Apple involvement: a "got it" action in demeter-ui, tapped on return. Deliberately not built now — it means ticking each item off twice, in Reminders and again in demeter, which is a worse daily cost than the occasional unwanted repeat. Recorded as the escape hatch, and as the first thing that would justify revisiting D4.

## Risks / Trade-offs

**R0. "Store" means chain today and will not later.** Recorded 2026-08-26 from the Ariadne session.

Demeter's `merchant_id` is a chain — Metro, IGA — so grouping by merchant is unambiguous as written. Ariadne's model anchors a Store to an individual **franchise**, with chain as a rollup attribute, and a flyer price is therefore recorded as `Regional(chainId, area)`: one fact covering a set of franchises, not fanned onto members at write time.

Every franchise of one chain in one region shares a single flyer, so their prices are identical **by construction**. A trip view keyed on franchise after that migration would render eight identical Metros and present a modelling artefact as a choice.

So this change's grouping dimension is the **chain**, for as long as its inputs are flyer-derived. The franchise becomes meaningful only when a price is franchise-exact, and the only source that will ever be is a receipt — franchise-level sales are invisible to Flipp.

Nothing to do now; the requirement below is correct against today's data. Written down because the ambiguity appears at migration, months after the reasoning would otherwise have been forgotten, and it fails by looking like a longer list rather than by erroring.

**R1. Retention.** Match results are per run per watch per matching observation — 270 rows for 2026-08-26 with three watches, and it scales with the watchlist. It needs a retention policy written at the same time as the table, not bolted on once the table is large. Note the asymmetry with `price_observation`: observations are irreplaceable because flyers expire, but match results are *derived* and could in principle be recomputed, so they are the cheaper thing to expire.

**R2. A stale trip.** The view shows the last run's decisions, which may be hours old and, late in a flyer week, may include deals that have since ended. Every row must carry the run it came from and the deal's `validTo`, so a trip planned on Wednesday evening cannot silently present Tuesday morning's world as current.

**R3. The Shortcut is unversioned and lives outside the repo.** It is a user-installed artifact that will drift from whatever the endpoint returns. Keep the payload boring and additive, treat it as a public contract, and expect no way to migrate it except telling the user to reinstall.

**R4. "Was on your list" ages badly.** It is exactly right the next day and increasingly odd after a month — an item last exported in June, resurfacing in September, labelled with a date nobody remembers deciding anything about. Consider a horizon past which the label is dropped rather than shown.

**R5. The phone is not on the tailnet.** Two nodes today, this Mac and `mimir`. Until the phone joins, the export runs only on the Mac. Not this feature's problem to solve, but it is this feature's problem if nobody does.

## Migration Plan

No migration. Two new tables, one new grant, additive endpoints, a new UI view. Nothing existing changes shape.

Sequencing matters in one place only: match results must be persisted by a run *before* the trip view has anything to read, so the first useful trip is the morning after that ships. Worth knowing rather than debugging.

## Open Questions

**Q1. What qualifies for a trip?** — **ANSWERED, 2026-08-26 (Calvin): whatever is currently alertable.**

A trip is *"push the button, get the list on my phone, tick things off in the shop; anything I do not tick off stays in alerts."* So the trip view is a projection of the same set alerting works from, and exporting is not a decision about the deal — see D7.

**Q2. Does the trip view have a window of its own?** — **ANSWERED, 2026-08-26: `validFrom <= now <= validTo`.**

Expired deals are excluded, and so are deals whose window has not opened. You are leaving now; a flyer starting tomorrow would send you out for a price that is not live yet, which is a worse error than omitting it, because it is only discoverable at the shelf.

**Q3. Where does the export record live?** — **ANSWERED, 2026-08-26: a table, with a narrow grant.**

To label a repeat *"last sent Aug 25"*, demeter must remember sending it, and that memory is a write. The browser would need no grant, but the record would then be per-device: a trip planned on the Mac would be invisible on the phone, which is exactly where the label needs to appear. So it is server-side, and `demeter_watch` gains `INSERT` and `SELECT` on that one table and nothing else — no `UPDATE`, no `DELETE`, because an export is a fact about something that already happened.

**Q4. Retention on match results.** — **ANSWERED, 2026-08-26: 90 days.**

The deal verdict's history window is 56 days, so nothing older can affect a current judgment. 90 leaves margin. Match results are derived, unlike observations, which cannot be re-fetched once flyers expire — that asymmetry is the whole reason they are safe to expire at all.
