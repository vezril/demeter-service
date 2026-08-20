package demeter.watchlist

import cats.effect.kernel.MonadCancelThrow
import cats.syntax.all._
import demeter.foundations.{DealWatchError, MerchantId, Money}
import doobie._
import doobie.implicits._
import doobie.postgres.implicits._

/** Persistence for the watchlist (04.1).
  *
  * Lives in this module rather than in `persistence` because 03 sits below 04
  * and cannot see `WatchItem`; the DDL itself is in `persistence.Schema` so all
  * migrations stay in one ordered place. Mirrors how pricehistory owns its own
  * queries over the same database.
  *
  * Terms and merchant scopes are Postgres arrays rather than child tables: a
  * personal watchlist is a handful of rows, and arrays keep both the queries and
  * a hand-written INSERT readable.
  */
final case class WatchLoad(items: List[WatchItem], rejected: List[(String, WatchItem.InvalidWatch)]) {
  def hasRejects: Boolean = rejected.nonEmpty
}

trait WatchStore[F[_]] {

  /** Every watch, valid or not — `rejected` names rows the domain refuses. */
  def load: F[WatchLoad]

  /** The watches the daily run should match on. */
  def active: F[List[WatchItem]]

  def upsert(watch: WatchItem): F[Either[DealWatchError, Unit]]

  def setActive(id: WatchId, isActive: Boolean): F[Either[DealWatchError, Boolean]]

  def delete(id: WatchId): F[Either[DealWatchError, Boolean]]
}

final class DoobieWatchStore[F[_]: MonadCancelThrow](xa: Transactor[F]) extends WatchStore[F] {

  def load: F[WatchLoad] = rows.map(build).transact(xa)

  def active: F[List[WatchItem]] =
    rows.map(rs => build(rs.filter(_.active)).items).transact(xa)

  def upsert(watch: WatchItem): F[Either[DealWatchError, Unit]] =
    sql"""INSERT INTO watch_item
            (id, label, terms, merchant_ids, max_price_cents, require_sale, min_discount_pct, active)
          VALUES (
            ${watch.id.value}, ${watch.label}, ${watch.terms.toList},
            ${watch.merchants.toList.map(_.value)}, ${watch.maxPrice.map(_.cents)},
            ${watch.requireSale}, ${watch.minDiscountPct}, ${watch.active}
          )
          ON CONFLICT (id) DO UPDATE SET
            label            = EXCLUDED.label,
            terms            = EXCLUDED.terms,
            merchant_ids     = EXCLUDED.merchant_ids,
            max_price_cents  = EXCLUDED.max_price_cents,
            require_sale     = EXCLUDED.require_sale,
            min_discount_pct = EXCLUDED.min_discount_pct,
            active           = EXCLUDED.active""".update.run.void
      .transact(xa)
      .map(_ => Right(()): Either[DealWatchError, Unit])
      .recover { case e => Left(DealWatchError.StoreUnavailable(e.toString)) }

  def setActive(id: WatchId, isActive: Boolean): F[Either[DealWatchError, Boolean]] =
    sql"UPDATE watch_item SET active = $isActive WHERE id = ${id.value}".update.run
      .transact(xa)
      .map(n => Right(n > 0): Either[DealWatchError, Boolean])
      .recover { case e => Left(DealWatchError.StoreUnavailable(e.toString)) }

  def delete(id: WatchId): F[Either[DealWatchError, Boolean]] =
    sql"DELETE FROM watch_item WHERE id = ${id.value}".update.run
      .transact(xa)
      .map(n => Right(n > 0): Either[DealWatchError, Boolean])
      .recover { case e => Left(DealWatchError.StoreUnavailable(e.toString)) }

  private def rows: ConnectionIO[List[WatchRow]] =
    sql"""SELECT id, label, terms, merchant_ids, max_price_cents, require_sale, min_discount_pct, active
          FROM watch_item ORDER BY id""".query[WatchRow].to[List]

  /** Rows are re-validated through WatchItem.of on the way out rather than
    * trusted: the table's CHECK constraints and the domain rules are two
    * separate statements of the same invariant, and a row that somehow slips
    * past the database is reported, never silently dropped or force-built.
    */
  private def build(rs: List[WatchRow]): WatchLoad = {
    val results = rs.map(r => r.id -> r.toDomain)
    WatchLoad(
      items = results.collect { case (_, Right(w)) => w },
      rejected = results.collect { case (id, Left(e)) => id -> e },
    )
  }
}

private final case class WatchRow(
    id: String,
    label: String,
    terms: List[String],
    merchantIds: List[Int],
    maxPriceCents: Option[Long],
    requireSale: Boolean,
    minDiscountPct: Option[Int],
    active: Boolean,
) {
  def toDomain: Either[WatchItem.InvalidWatch, WatchItem] =
    WatchItem.of(
      id = WatchId(id),
      label = label,
      terms = terms,
      merchants = merchantIds.map(MerchantId(_)).toSet,
      maxPrice = maxPriceCents.map(Money.cents(_)),
      requireSale = requireSale,
      minDiscountPct = minDiscountPct,
      active = active,
    )
}
