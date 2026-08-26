#!/usr/bin/env bash
#
# SPIKE — does iCloud Reminders round-trip over CalDAV?
#
# Throwaway investigation tool. NOT demeter code, not for the repo.
#
# The question this exists to answer is NOT "can we add an item" -- that part is
# almost certainly fine. It is:
#
#     can demeter SEE that you deleted something?
#
# Because if it cannot, then any unattended push design re-adds every item you
# throw away, every single day, and looks like it is working perfectly.
#
# Credentials: read from the macOS Keychain, never from argv or the environment,
# so they stay out of `ps` and out of shell history. Store it once:
#
#   security add-generic-password -a "you@icloud.com" -s demeter-caldav-spike -w
#
# (-w with no value prompts interactively. Use an APP-SPECIFIC password from
# appleid.apple.com, never your real one, and revoke it when the spike is done.)
#
# Usage:
#   ./caldav-spike.sh discover                 # principal + calendar home
#   ./caldav-spike.sh lists                    # which collections hold VTODO
#   ./caldav-spike.sh dump   <collection-url>  # the reminders in one list
#   ./caldav-spike.sh add    <collection-url> "Lactantia butter $4.99" [YYYY-MM-DD]
#   ./caldav-spike.sh watch  <collection-url>  # poll + diff; delete on your phone
#
set -euo pipefail

APPLE_ID="${APPLE_ID:-}"
if [[ -z "$APPLE_ID" ]]; then
  echo "set APPLE_ID=you@icloud.com first (the account, not the password)" >&2
  exit 2
fi

pw() { security find-generic-password -a "$APPLE_ID" -s demeter-caldav-spike -w 2>/dev/null; }
if [[ -z "$(pw)" ]]; then
  echo "no app-specific password in Keychain. Store one with:" >&2
  echo "  security add-generic-password -a \"$APPLE_ID\" -s demeter-caldav-spike -w" >&2
  exit 2
fi

# Credentials go in a 0600 netrc, never in argv, so they stay out of `ps`.
#
# The status check is NOT ceremony. iCloud answers a bad password with an HTML
# error page, and an HTML error page still parses as XML -- so without this,
# `lists` printed its header, found no <response> elements, and rendered a
# failed login as "you have no Reminders lists". Same shape as every other bug
# this project has turned up: the failure arrives looking like an empty answer.
dav() {
  local method="$1" url="$2" depth="${3:-0}" body="${4:-}" ctype="${5:-application/xml; charset=utf-8}"
  local host; host="$(printf '%s' "$url" | sed -E 's#https://([^/]+).*#\1#')"
  local nrc; nrc="$(mktemp)"; chmod 600 "$nrc"
  printf 'machine %s login %s password %s\n' "$host" "$APPLE_ID" "$(pw)" > "$nrc"
  local out rc
  out="$(curl -sS -m 30 --netrc-file "$nrc" -X "$method" "$url" \
          -H "Depth: $depth" -H "Content-Type: $ctype" \
          -w '\n%{http_code}' ${body:+--data-binary "$body"})"; rc=$?
  rm -f "$nrc"
  [[ $rc -ne 0 ]] && { echo "!! curl failed ($rc) on $method $url" >&2; return 1; }
  local code="${out##*$'\n'}" payload="${out%$'\n'*}"
  case "$code" in
    2*) printf '%s' "$payload" ;;
    401) echo "!! 401 -- the app-specific password was rejected. Regenerate it at appleid.apple.com." >&2; return 1 ;;
    *)   echo "!! HTTP $code on $method $url" >&2
         printf '%s' "$payload" | head -c 300 >&2; echo >&2; return 1 ;;
  esac
}

xml() { python3 -c '
import sys, xml.etree.ElementTree as ET
ns = {"d": "DAV:", "c": "urn:ietf:params:xml:ns:caldav"}
mode = sys.argv[1]
try:
    root = ET.fromstring(sys.stdin.read())
except ET.ParseError as e:
    print(f"  !! not XML ({e}) -- likely an auth failure or an HTML error page", file=sys.stderr); sys.exit(1)

if mode == "principal":
    for h in root.iter("{DAV:}current-user-principal"):
        for u in h.iter("{DAV:}href"): print(u.text)
elif mode == "home":
    for h in root.iter("{urn:ietf:params:xml:ns:caldav}calendar-home-set"):
        for u in h.iter("{DAV:}href"): print(u.text)
elif mode == "collections":
    for resp in root.iter("{DAV:}response"):
        href = resp.find("d:href", ns)
        name = resp.find(".//d:displayname", ns)
        comps = [c.get("name") for c in resp.iter("{urn:ietf:params:xml:ns:caldav}comp")]
        if not comps: continue
        todo = "VTODO" in comps
        mark = "TODO <-- reminders" if todo else "     " + ",".join(comps)
        label = name.text if (name is not None and name.text) else "(unnamed)"
        print("%-24s %-28s %s" % (mark, label, href.text))
elif mode == "todos":
    for resp in root.iter("{DAV:}response"):
        href = resp.find("d:href", ns)
        data = resp.find(".//c:calendar-data", ns)
        if data is None or not data.text: continue
        uid = summ = status = due = ""
        for line in data.text.splitlines():
            if line.startswith("UID:"): uid = line[4:].strip()
            elif line.startswith("SUMMARY:"): summ = line[8:].strip()
            elif line.startswith("STATUS:"): status = line[7:].strip()
            elif line.startswith("DUE"): due = line.split(":",1)[-1].strip()
        print("%s\t%s\t%s\t%s" % (uid, status or "NEEDS-ACTION", due, summ))
' "$1"; }

CAL=https://caldav.icloud.com

cmd_discover() {
  echo "== current-user-principal =="
  local p
  p="$(dav PROPFIND "$CAL/" 0 '<?xml version="1.0"?><d:propfind xmlns:d="DAV:"><d:prop><d:current-user-principal/></d:prop></d:propfind>' | xml principal)"
  echo "  $p"
  echo "== calendar-home-set =="
  dav PROPFIND "$CAL$p" 0 '<?xml version="1.0"?><d:propfind xmlns:d="DAV:" xmlns:c="urn:ietf:params:xml:ns:caldav"><d:prop><c:calendar-home-set/></d:prop></d:propfind>' | xml home
}

cmd_lists() {
  local home; home="$(cmd_discover | tail -1 | tr -d ' ')"
  echo "== collections under $home =="
  echo "   (only VTODO ones are Reminders lists; VEVENT ones are Calendars)"
  dav PROPFIND "$home" 1 '<?xml version="1.0"?><d:propfind xmlns:d="DAV:" xmlns:c="urn:ietf:params:xml:ns:caldav"><d:prop><d:displayname/><c:supported-calendar-component-set/></d:prop></d:propfind>' | xml collections
}

cmd_dump() {
  local col="$1"
  dav REPORT "$col" 1 '<?xml version="1.0"?><c:calendar-query xmlns:d="DAV:" xmlns:c="urn:ietf:params:xml:ns:caldav"><d:prop><d:getetag/><c:calendar-data/></d:prop><c:filter><c:comp-filter name="VCALENDAR"><c:comp-filter name="VTODO"/></c:comp-filter></c:filter></c:calendar-query>' | xml todos
}

cmd_add() {
  local col="$1" title="$2" due="${3:-}"
  local uid; uid="demeter-spike-$(od -An -N8 -tx1 /dev/urandom | tr -d ' \n')"
  local stamp; stamp="$(date -u +%Y%m%dT%H%M%SZ)"
  local duel=""
  [[ -n "$due" ]] && duel="DUE;VALUE=DATE:$(printf '%s' "$due" | tr -d '-')
"
  local ics="BEGIN:VCALENDAR
VERSION:2.0
PRODID:-//demeter//caldav-spike//EN
BEGIN:VTODO
UID:$uid
DTSTAMP:$stamp
SUMMARY:$title
${duel}DESCRIPTION:added by the demeter caldav spike
END:VTODO
END:VCALENDAR"
  echo "PUT $uid"
  dav PUT "${col%/}/$uid.ics" 0 "$ics" "text/calendar; charset=utf-8" && echo "  (no body on success is normal; 201/204)"
  echo "-> now look at Reminders on your phone."
}

# The one that matters.
cmd_watch() {
  local col="$1" prev="" now
  echo "polling every 10s. Now go and DELETE one of these on your iPhone."
  echo "if the deletion shows up here, demeter can tell the difference between"
  echo "'not added yet' and 'added, and they threw it away'."
  echo
  while true; do
    now="$(cmd_dump "$col" || true)"
    if [[ "$now" != "$prev" ]]; then
      echo "--- $(date +%H:%M:%S) ---"
      if [[ -n "$prev" ]]; then
        diff <(printf '%s\n' "$prev") <(printf '%s\n' "$now") | sed 's/^</  GONE   /; s/^>/  NEW    /' | grep -E "GONE|NEW" || true
      else
        printf '%s\n' "$now" | sed 's/^/  have   /'
      fi
      prev="$now"
    fi
    sleep 10
  done
}

case "${1:-}" in
  discover) cmd_discover ;;
  lists)    cmd_lists ;;
  dump)     cmd_dump "${2:?collection url}" ;;
  add)      cmd_add "${2:?collection url}" "${3:?title}" "${4:-}" ;;
  watch)    cmd_watch "${2:?collection url}" ;;
  *) sed -n '2,32p' "$0" ;;
esac
