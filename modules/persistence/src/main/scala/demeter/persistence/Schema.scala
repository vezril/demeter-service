package demeter.persistence

import cats.effect.kernel.MonadCancelThrow
import cats.syntax.all._
import doobie._
import doobie.implicits._

/** Spec 03.1 — the database shape. Chosen to make three things cheap:
  * (a) replaying normalization from archived raws, (b) price history keyed on
  * ProductKey, (c) diffing flyers week over week.
  *
  * Invariants enforced structurally:
  *  - every price_observation references its raw_response (raw before parsed);
  *  - effective_cents NULL is a first-class, queryable state;
  *  - UNIQUE (product_key, flyer_id, observed_at) makes re-runs idempotent;
  *  - no flyer imagery anywhere (image *URLs* inside archived JSON bodies are
  *    facts and fine; images are never fetched or stored).
  */
object Schema {

  val ddl: List[Fragment] = List(
    sql"""CREATE TABLE IF NOT EXISTS raw_response (
            id           bigserial PRIMARY KEY,
            source       text NOT NULL,
            kind         text NOT NULL,
            url          text NOT NULL,
            postal_code  text NOT NULL,
            locale       text NOT NULL,
            fetched_at   timestamptz NOT NULL,
            content_type text NOT NULL,
            body         bytea NOT NULL,
            body_sha256  bytea NOT NULL
          )""",
    sql"""CREATE INDEX IF NOT EXISTS raw_response_dedup_idx
            ON raw_response (source, kind, postal_code, locale, body_sha256, fetched_at)""",
    sql"""CREATE TABLE IF NOT EXISTS merchant (
            id   int PRIMARY KEY,
            name text NOT NULL
          )""",
    sql"""CREATE TABLE IF NOT EXISTS flyer (
            id              bigint PRIMARY KEY,
            merchant_id     int NOT NULL,
            name            text NOT NULL,
            valid_from      timestamptz NOT NULL,
            valid_to        timestamptz NOT NULL,
            postal_code     text NOT NULL,
            locale          text NOT NULL,
            first_seen_at   timestamptz NOT NULL,
            last_seen_at    timestamptz NOT NULL,
            raw_response_id bigint REFERENCES raw_response(id)
          )""",
    sql"""CREATE TABLE IF NOT EXISTS flyer_fetch_ledger (
            flyer_id        bigint PRIMARY KEY,
            window_from     timestamptz NOT NULL,
            window_to       timestamptz NOT NULL,
            fetched_at      timestamptz NOT NULL,
            raw_response_id bigint NOT NULL REFERENCES raw_response(id)
          )""",
    sql"""CREATE TABLE IF NOT EXISTS product (
            key             text PRIMARY KEY,
            merchant_id     int NOT NULL,
            display_name_en text,
            display_name_fr text,
            size_qty        numeric,
            size_unit       text,
            pack_count      int,
            first_seen_at   timestamptz NOT NULL
          )""",
    sql"""CREATE TABLE IF NOT EXISTS price_observation (
            id              bigserial PRIMARY KEY,
            product_key     text NOT NULL REFERENCES product(key),
            merchant_id     int NOT NULL,
            flyer_id        bigint NOT NULL,
            observed_at     timestamptz NOT NULL,
            raw_name        text NOT NULL,
            display_name_en text,
            display_name_fr text,
            effective_cents bigint,
            price_basis     text NOT NULL,
            original_cents  bigint,
            size_qty        numeric,
            size_unit       text,
            pack_count      int,
            unit_cents      bigint,
            unit_basis      text,
            sale_text       text,
            valid_from      timestamptz NOT NULL,
            valid_to        timestamptz NOT NULL,
            price_confidence text NOT NULL,
            match_confidence text NOT NULL,
            raw_response_id bigint NOT NULL REFERENCES raw_response(id),
            UNIQUE (product_key, flyer_id, observed_at)
          )""",
    // The watchlist itself (04.1). The DDL lives here so every migration is in one
    // ordered place; the typed store lives in the watchlist module, which is the
    // only layer that can see WatchItem. The CHECKs mirror WatchItem.of's
    // validation, so a hand-written INSERT cannot create a watch the domain
    // would reject.
    sql"""CREATE TABLE IF NOT EXISTS watch_item (
            id               text PRIMARY KEY,
            label            text NOT NULL,
            terms            text[] NOT NULL,
            exclude_terms    text[] NOT NULL DEFAULT '{}',
            merchant_ids     int[] NOT NULL DEFAULT '{}',
            max_price_cents  bigint,
            require_sale     boolean NOT NULL DEFAULT true,
            min_discount_pct int,
            active           boolean NOT NULL DEFAULT true,
            created_at       timestamptz NOT NULL DEFAULT now(),
            CONSTRAINT watch_item_label_non_empty CHECK (btrim(label) <> ''),
            CONSTRAINT watch_item_terms_non_empty CHECK (cardinality(terms) > 0),
            CONSTRAINT watch_item_discount_range
              CHECK (min_discount_pct IS NULL OR min_discount_pct BETWEEN 1 AND 100),
            CONSTRAINT watch_item_max_price_non_negative
              CHECK (max_price_cents IS NULL OR max_price_cents >= 0)
          )""",
    sql"""CREATE INDEX IF NOT EXISTS watch_item_active_idx ON watch_item (active)""",
    // New watches default to requiring a genuine sale; existing rows keep
    // whatever was chosen for them.
    sql"ALTER TABLE watch_item ALTER COLUMN require_sale SET DEFAULT true",
    // Exclusion terms (04.1). ADD COLUMN IF NOT EXISTS is idempotent on its own,
    // so this needs no guard.
    sql"ALTER TABLE watch_item ADD COLUMN IF NOT EXISTS exclude_terms text[] NOT NULL DEFAULT '{}'",
    // What has already been alerted (05.2). The primary key IS the dedup key —
    // watch + product + the flyer's validity window — so the "same deal, same
    // window, only once" rule is enforced by the schema, not just by code.
    // alerted_cents carries the price we last told you about, which is what
    // makes "the deal got better" decidable across a restart.
    sql"""CREATE TABLE IF NOT EXISTS alert_ledger (
            watch_id      text NOT NULL,
            product_key   text NOT NULL,
            window_from   timestamptz NOT NULL,
            window_to     timestamptz NOT NULL,
            alerted_cents bigint,
            alerted_at    timestamptz NOT NULL,
            PRIMARY KEY (watch_id, product_key, window_from, window_to)
          )""",
    // One row per completed daily run (08.3). Until now the report existed only
    // as a log line, so "was yesterday's run healthy?" was answerable only by
    // whoever still had the pod's stdout -- and a delivery that succeeded while
    // being counted as a failure was invisible without cross-checking the
    // broker by hand. Written at the end of a run, never updated.
    sql"""CREATE TABLE IF NOT EXISTS run_report (
            id                    bigserial PRIMARY KEY,
            started_at            timestamptz NOT NULL,
            finished_at           timestamptz NOT NULL,
            elapsed_seconds       bigint,
            flyers_listed         integer NOT NULL,
            flyers_selected       integer NOT NULL,
            flyers_fetched        integer NOT NULL,
            flyers_failed         integer NOT NULL,
            items_parsed          integer NOT NULL,
            items_dropped         integer NOT NULL,
            observations_inserted integer NOT NULL,
            observations_skipped  integer NOT NULL,
            matches               integer NOT NULL,
            alerts_delivered      integer NOT NULL,
            alerts_suppressed     integer NOT NULL,
            -- reason -> count. The single most valuable column here: a bare
            -- total cannot distinguish a price ceiling that is too tight from an
            -- empty history from having already told you.
            suppressed_by_reason  jsonb NOT NULL DEFAULT '{}'::jsonb,
            -- NULL means "could not tell", which is not the same as zero.
            alert_audience        integer,
            degraded_sources      text[] NOT NULL DEFAULT '{}',
            failures              text[] NOT NULL DEFAULT '{}',
            partial               boolean NOT NULL DEFAULT false
          )""",
    sql"""CREATE INDEX IF NOT EXISTS run_report_finished_idx ON run_report (finished_at DESC)""",
    sql"""CREATE INDEX IF NOT EXISTS alert_ledger_window_idx ON alert_ledger (window_to)""",
    sql"""CREATE INDEX IF NOT EXISTS price_observation_history_idx
            ON price_observation (product_key, observed_at DESC)""",
    sql"""CREATE INDEX IF NOT EXISTS price_observation_window_idx
            ON price_observation (valid_from, valid_to)""",
  )

  /** In-place migrations for databases created before a shape change. Kept
    * separate from `ddl` because CREATE TABLE IF NOT EXISTS silently does
    * nothing to an existing table — which is exactly how a schema change turns
    * into a runtime column-not-found at 3am.
    */
  val migrations: List[Fragment] = List(
    // Confidence was one column collapsing two unrelated judgements.
    //
    // Guarded on the old column still existing, because migrations run on EVERY
    // boot: an unguarded backfill referencing `confidence` succeeds once and then
    // fails forever after the DROP, which means the service starts once and never
    // again. Existing rows only ever stored the minimum of the two judgements, so
    // both are backfilled from it — conservative, and honest that the components
    // are not recoverable.
    sql"""DO $$$$
          BEGIN
            IF EXISTS (
              SELECT 1 FROM information_schema.columns
              WHERE table_name = 'price_observation' AND column_name = 'confidence'
            ) THEN
              ALTER TABLE price_observation ADD COLUMN IF NOT EXISTS price_confidence text;
              ALTER TABLE price_observation ADD COLUMN IF NOT EXISTS match_confidence text;
              UPDATE price_observation
                 SET price_confidence = COALESCE(price_confidence, confidence),
                     match_confidence = COALESCE(match_confidence, confidence);
              ALTER TABLE price_observation DROP COLUMN confidence;
              ALTER TABLE price_observation ALTER COLUMN price_confidence SET NOT NULL;
              ALTER TABLE price_observation ALTER COLUMN match_confidence SET NOT NULL;
            END IF;
          END $$$$;"""
  )

  def migrate[F[_]: MonadCancelThrow](xa: Transactor[F]): F[Unit] =
    (ddl ++ migrations).traverse_(_.update.run).transact(xa)
}
