#!/usr/bin/env bash
#
# Demeter -> Ariadne backfill export (migration step 2).
#
# Dumps demeter's own tables in demeter's own shape. Deliberately does NOT
# transform into Ariadne's model: their schema is still moving, and a transform
# written now would be written against a target that changes. They resolve;
# we hand over facts.
#
#   ./export-for-ariadne.sh check     counts + column lists, writes nothing
#   ./export-for-ariadne.sh dump DIR  the actual CSVs
#   ./export-for-ariadne.sh archive DIR  raw response bytes too (22 MB, optional)
#
# Read-only against production. Uses the `demeter` role because the export needs
# every table; demeter_read would also do for everything except raw_response.
set -euo pipefail

CTX=${KCTX:-tailnet}
NS=${KNS:-demeter}
STS=sts/demeter-demeter-service-postgresql

psql_() { kubectl --context "$CTX" -n "$NS" exec -i "$STS" -- psql -U demeter -d demeter "$@"; }

# The tables that carry facts. flyer and merchant are dimensions Ariadne needs to
# resolve an observation; product is demeter's identity, which Ariadne SUPERSEDES
# -- it is exported so the id map can be built, not so it can be adopted.
TABLES="merchant flyer product price_observation"

cmd_check() {
  echo "== row counts =="
  for t in $TABLES raw_response; do
    printf '  %-20s %s\n' "$t" "$(psql_ -tAc "SELECT count(*) FROM $t")"
  done
  echo
  echo "== columns, so the receiving end can be written against the real shape =="
  for t in $TABLES; do
    printf '  %s:\n    %s\n' "$t" "$(psql_ -tAc "
      SELECT string_agg(column_name || ' ' || data_type, ', ' ORDER BY ordinal_position)
      FROM information_schema.columns WHERE table_name='$t'")"
  done
  echo
  echo "== provenance is a join, not a guess =="
  psql_ -tAc "
    SELECT '  observations with raw_response_id: ' || count(*) FILTER (WHERE raw_response_id IS NOT NULL)
        || ' of ' || count(*) FROM price_observation"
  echo
  echo "== confidences must survive: they are the only record of how much to trust a row =="
  psql_ -c "
    SELECT price_confidence, match_confidence, count(*)
    FROM price_observation GROUP BY 1,2 ORDER BY 3 DESC LIMIT 6"
}

cmd_dump() {
  local out=${1:?usage: dump DIR}
  mkdir -p "$out"
  for t in $TABLES; do
    echo "-> $t"
    psql_ -c "\copy (SELECT * FROM $t) TO STDOUT WITH (FORMAT csv, HEADER true)" > "$out/$t.csv"
    printf '   %s rows, %s\n' "$(($(wc -l < "$out/$t.csv") - 1))" "$(du -h "$out/$t.csv" | cut -f1)"
  done
  # A manifest, so a partial or stale export is detectable rather than assumed whole.
  #
  # raw_response_without_observations is the diff's own baseline, carried here on
  # purpose: archived responses that produced nothing are the flyers 0.6.0 lost
  # whole, and they are exactly the rows a decoder diff will find items in where
  # this export has none. Recording the LIVE value means the diff describes itself
  # rather than depending on a conversation -- and if Replay has since re-derived
  # them, this number drops and the expected delta shrinks with it.
  {
    echo "exported_at=$(date -u +%FT%TZ)"
    echo "source=demeter-service production (k3s/$NS)"
    for t in $TABLES; do echo "$t=$(psql_ -tAc "SELECT count(*) FROM $t")"; done
    echo "raw_response=$(psql_ -tAc "SELECT count(*) FROM raw_response")"
    echo "raw_response_without_observations=$(psql_ -tAc "
      SELECT count(*) FROM raw_response rr
      WHERE NOT EXISTS (SELECT 1 FROM price_observation po WHERE po.raw_response_id = rr.id)")"
  } > "$out/MANIFEST"
  echo "-> MANIFEST"
  cat "$out/MANIFEST" | sed 's/^/   /'
}

# The raw archive. Optional and separate: it is 22 MB of bytea and Ariadne owns
# replay going FORWARD regardless. Exporting it only matters if they want to be
# able to re-derive demeter's HISTORY too, rather than trusting the observations.
cmd_archive() {
  local out=${1:?usage: archive DIR}
  mkdir -p "$out"
  echo "-> raw_response (base64-encoded body; CSV cannot carry raw bytea)"
  psql_ -c "\copy (SELECT id, source, kind, fetched_at, encode(body,'base64') AS body_b64 FROM raw_response) TO STDOUT WITH (FORMAT csv, HEADER true)" > "$out/raw_response.csv"
  printf '   %s rows, %s\n' "$(($(wc -l < "$out/raw_response.csv") - 1))" "$(du -h "$out/raw_response.csv" | cut -f1)"
}

case "${1:-check}" in
  check)   cmd_check ;;
  dump)    cmd_dump "${2:-}" ;;
  archive) cmd_archive "${2:-}" ;;
  *) sed -n '2,20p' "$0" ;;
esac
