package demeter.alerting

import java.time.Instant

import cats.effect.kernel.MonadCancelThrow
import cats.syntax.all._
import demeter.foundations.{DealWatchError, Money, ProductKey}
import demeter.watchlist.WatchId
import doobie._
import doobie.implicits._
import doobie.postgres.implicits._

/** Durable backing for alert dedup (05.2).
  *
  * The decision itself stays pure — `AlertDedup.isNew` takes prior state as a
  * value — but that state has to outlive the process. Held only in memory, a
  * restart mid-week re-alerts every deal already sent, which is precisely the
  * "you mute it in a week" failure 05.2 exists to prevent.
  *
  * Lives in the alerting module for the same reason the watch store lives in
  * watchlist: `AlertKey` is a 05 type and persistence (03) sits below it. The
  * DDL is in `persistence.Schema` so migrations stay in one ordered place.
  */
trait AlertLedger[F[_]] {

  /** Prior alerts for flyer windows that are still open at `now`.
    *
    * Closed windows are irrelevant to dedup — a new window is news again (05.2)
    * — so loading only open ones keeps the working set small no matter how much
    * history accumulates.
    */
  def openAt(now: Instant): F[Map[AlertKey, AlertRecord]]

  def record(entry: AlertRecord): F[Either[DealWatchError, Unit]]

  /** Housekeeping: drop records for windows that closed before `cutoff`. */
  def prune(cutoff: Instant): F[Int]
}

final class DoobieAlertLedger[F[_]: MonadCancelThrow](xa: Transactor[F]) extends AlertLedger[F] {

  def openAt(now: Instant): F[Map[AlertKey, AlertRecord]] =
    sql"""SELECT watch_id, product_key, window_from, window_to, alerted_cents, alerted_at
          FROM alert_ledger WHERE window_to >= $now"""
      .query[(String, String, Instant, Instant, Option[Long], Instant)]
      .to[List]
      .transact(xa)
      .map(_.map { case (watchId, productKey, from, to, cents, at) =>
        val key = AlertKey(WatchId(watchId), ProductKey(productKey), from, to)
        key -> AlertRecord(key, cents.map(Money.cents(_)), at)
      }.toMap)

  /** Upsert, because a price drop re-alerts (05.2) and the ledger must then
    * carry the NEW, lower price — otherwise a later, smaller drop would be
    * compared against a stale figure and suppressed.
    */
  def record(entry: AlertRecord): F[Either[DealWatchError, Unit]] =
    sql"""INSERT INTO alert_ledger
            (watch_id, product_key, window_from, window_to, alerted_cents, alerted_at)
          VALUES (
            ${entry.key.watchId.value}, ${entry.key.productKey.value},
            ${entry.key.windowFrom}, ${entry.key.windowTo},
            ${entry.alertedPrice.map(_.cents)}, ${entry.alertedAt}
          )
          ON CONFLICT (watch_id, product_key, window_from, window_to) DO UPDATE SET
            alerted_cents = EXCLUDED.alerted_cents,
            alerted_at    = EXCLUDED.alerted_at""".update.run.void
      .transact(xa)
      .map(_ => Right(()): Either[DealWatchError, Unit])
      .recover { case e => Left(DealWatchError.StoreUnavailable(e.toString)) }

  def prune(cutoff: Instant): F[Int] =
    sql"DELETE FROM alert_ledger WHERE window_to < $cutoff".update.run.transact(xa)
}
