package demeter.insight

import java.time.Instant

import cats.effect.kernel.MonadCancelThrow
import cats.syntax.all._
import doobie._
import doobie.implicits._
import doobie.postgres.implicits._

/** Watches and the alerts they have produced.
  *
  * Both are small, flat tables owned by this query layer rather than borrowed
  * from a store, which is the case the design note was written for: a column
  * rename breaks this file at compile time instead of quietly changing what an
  * endpoint returns.
  */
trait WatchQueries[F[_]] {
  def watches: F[List[WatchView]]
  def alerts(limit: Int): F[List[AlertView]]
}

final class DbWatchQueries[F[_]: MonadCancelThrow](xa: Transactor[F]) extends WatchQueries[F] {

  def watches: F[List[WatchView]] =
    sql"""SELECT w.id, w.label, w.terms, w.exclude_terms, w.merchant_ids,
                 w.max_price_cents, w.require_sale, w.min_discount_pct, w.active,
                 COALESCE(a.sent, 0), a.last_alerted
            FROM watch_item w
            LEFT JOIN (
              SELECT watch_id, count(*) AS sent, max(alerted_at) AS last_alerted
                FROM alert_ledger GROUP BY watch_id
            ) a ON a.watch_id = w.id
           ORDER BY w.id"""
      .query[
        (
            String,
            String,
            List[String],
            List[String],
            List[Int],
            Option[Long],
            Boolean,
            Option[Int],
            Boolean,
            Int,
            Option[Instant],
        )
      ]
      .to[List]
      .transact(xa)
      .map(_.map { case (id, label, terms, excl, merchants, maxP, reqSale, minPct, active, sent, last) =>
        WatchView(id, label, terms, excl, merchants, maxP, reqSale, minPct, active, sent, last)
      })

  def alerts(limit: Int): F[List[AlertView]] =
    sql"""SELECT l.watch_id, w.label, l.product_key,
                 o.merchant_id, m.name, o.display_name_en,
                 l.alerted_cents, l.alerted_at, l.window_from, l.window_to
            FROM alert_ledger l
            LEFT JOIN watch_item w ON w.id = l.watch_id
            -- ProductKey is merchant-scoped, so this join cannot fan out across
            -- merchants. DISTINCT ON picks one row per key deterministically
            -- rather than multiplying the alert by its observations.
            LEFT JOIN LATERAL (
              SELECT DISTINCT ON (product_key) merchant_id, display_name_en
                FROM price_observation
               WHERE product_key = l.product_key
               ORDER BY product_key, observed_at DESC
            ) o ON true
            LEFT JOIN merchant m ON m.id = o.merchant_id
           ORDER BY l.alerted_at DESC
           LIMIT $limit"""
      .query[
        (
            String,
            Option[String],
            String,
            Option[Int],
            Option[String],
            Option[String],
            Option[Long],
            Instant,
            Instant,
            Instant,
        )
      ]
      .to[List]
      .transact(xa)
      .map(_.map { case (wid, label, key, merchant, mname, item, cents, at, from, to) =>
        AlertView(wid, label, key, merchant, mname, item, cents, at, from, to)
      })
}
