# Migration to Ariadne

Ariadne takes over "what does this cost"; demeter keeps "is that a good deal".
Facts move, judgments stay. Sequencing lives in `codex`'s `apps/ariadne` and in
`ariadne-service/docs/migration-demeter.md`; this directory holds only demeter's
half of the work.

## `export-for-ariadne.sh` — step 2 (backfill)

Read-only against production. Three modes:

    ./export-for-ariadne.sh check        counts and column lists, writes nothing
    ./export-for-ariadne.sh dump DIR     the CSVs plus a MANIFEST
    ./export-for-ariadne.sh archive DIR  raw response bytes, base64 (optional, ~22 MB)

It dumps **demeter's shape, with no transform**. Ariadne's schema is still
moving, and a transform written here would be written against a target that
changes; it would also put our guesses about their model inside our script.
Facts across the wire, resolution on their side.

`product` is exported so the Demeter-key → ProductId map can be built, **not**
for adoption — their resolver supersedes `ProductKeys`, which is retired at
cutover when the id map re-points `alert_ledger`, the watch keys and history.

### The MANIFEST is the point

A partial export that looks whole is the failure mode, so every dump carries
counts and a timestamp. It also carries `raw_response_without_observations`,
which is not corroboration of a number in a conversation — it *selects which
reading of Ariadne's expected-delta table applies*:

    = 9   Replay has not run over the archives that produced nothing.
          Their decoders will find items where this export has none, and that is
          demeter's fixed bugs showing, not a port defect.
    = 0   Those archives have been re-derived. Extras are unexplained and worth
          investigating.

A future reader seeing `0` and finding no extras should conclude the caveat was
**resolved, not overblown** — a stale caveat that reads as a false alarm teaches
people to discount the next one.

## Replay: do not run it against a populated table

`orchestration/runMain demeter.orchestration.Replay` re-derives observations
from archived bytes. Its scaladoc claims a replay is idempotent. **It is not.**

`DailyRun` stamps `observed_at = now` (the run's start); `Replay` stamps
`observed_at = raw.fetchedAt` (the archive's time). Uniqueness is
`(product_key, flyer_id, observed_at)`, so the two never collide and every
replayed row inserts fresh. Run against production on 2026-09-01 it took
`price_observation` from 35,088 rows to 72,544 — a complete duplicate history —
and recovered exactly one row that was genuinely missing.

Its stated purpose (spec 03.1, "delete every observation and rebuild from
raw_response alone") is sound: against an EMPTY table it does what it says. The
claim that fails is idempotency against a populated one.

The rows are separable if it happens again, because the two stamps cannot
coincide:

```sql
DELETE FROM price_observation po USING raw_response rr
 WHERE rr.id = po.raw_response_id AND po.observed_at = rr.fetched_at;
```

Take a dump first regardless — the README's own procedure — because this is the
one table that cannot be rebuilt from anywhere else.

### And the thing worth remembering over the mechanics

The three flyers 0.6.0 lost on 2026-08-26 were never permanently lost, and the
archive is not what saved them. `flyer_fetch_ledger` records only *successful*
fetches, so the failures were re-selected on the next run and fetched cleanly
under 0.6.1. The data had already healed a week before anyone tried to recover
it. Check whether a thing is still broken before repairing it.
