# Spikes

Throwaway investigation tools. Not part of the build, not run by CI, not
imported by anything. Kept because the question each answers tends to come
back, and rebuilding the probe costs more than storing it.

## `caldav-reminders.sh` — shelved 2026-08-26

Asks whether iCloud Reminders round-trips over CalDAV, in service of exporting
a shopping list to Apple Reminders (`openspec/changes/plan-shopping-trips`).

**Never run against a real account.** It was written, tested against synthetic
responses, and shelved when the design went a different way — the export runs
on-device instead, so demeter holds no Apple credential at all. See that
change's `design.md` D4 for why.

What was established without credentials:

- `caldav.icloud.com` is live and speaks real CalDAV: `DAV: 1, access-control,
  calendar-access, …`, with `PROPFIND PROPPATCH REPORT PUT DELETE MKCOL` allowed.
- Auth is `WWW-Authenticate: Basic realm="MMCalDav"` — an app-specific password
  over TLS, nothing Apple-proprietary.
- `.well-known/caldav` redirects to `www.icloud.com` and then 400s. Discovery
  goes directly to `caldav.icloud.com`; this is the first thing that wastes an
  hour otherwise.

What it would still answer, if the question returns:

1. `lists` — are Reminders lists exposed at all? Rows marked `TODO <-- reminders`.
   If only `VEVENT` appears, the approach is dead and nothing else matters.
2. `add` — does an item arrive, and does iOS auto-file it into a Groceries
   department?
3. `watch` — **the decisive one.** Tick an item off on the phone; if the change
   shows up here, demeter can distinguish "never sent" from "sent, and dealt
   with". That is the capability the credential-free design gives up.

Credentials are read from the macOS Keychain, never from argv or the
environment. Store one with:

    security add-generic-password -a "you@icloud.com" -s demeter-caldav-spike -w

Use an app-specific password from appleid.apple.com, and revoke it afterwards.

**The reason this stayed shelved** is not that CalDAV fails. It is that an
iCloud app-specific password is not scoped by iCloud to Reminders — it
authenticates to the account. Every other credential in this system is confined
by whatever holds it; `demeter_watch` is confined by PostgreSQL to one table.
This one would be the broadest thing in the cluster, in exchange for being able
to say "you bought this" instead of "this was on your list". Revisit if
something else — HealthKit was mentioned — makes an iCloud credential earn its
keep on its own.
