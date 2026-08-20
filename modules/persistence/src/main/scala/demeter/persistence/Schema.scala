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
            confidence      text NOT NULL,
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
            merchant_ids     int[] NOT NULL DEFAULT '{}',
            max_price_cents  bigint,
            require_sale     boolean NOT NULL DEFAULT false,
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
    sql"""CREATE INDEX IF NOT EXISTS alert_ledger_window_idx ON alert_ledger (window_to)""",
    sql"""CREATE INDEX IF NOT EXISTS price_observation_history_idx
            ON price_observation (product_key, observed_at DESC)""",
    sql"""CREATE INDEX IF NOT EXISTS price_observation_window_idx
            ON price_observation (valid_from, valid_to)""",
  )

  def migrate[F[_]: MonadCancelThrow](xa: Transactor[F]): F[Unit] =
    ddl.traverse_(_.update.run).transact(xa)
}
