package demeter.persistence

import java.time.Instant

import scala.concurrent.duration._

import cats.effect.kernel.MonadCancelThrow
import cats.syntax.all._
import demeter.foundations._
import doobie._
import doobie.implicits._
import doobie.postgres.implicits._

/** Spec 03.4 — decide, each day, which flyers are new or changed and therefore
  * worth the expensive per-flyer items call. Turns ~120 daily heavy fetches
  * into ~15 a week.
  */
final case class LedgerEntry(flyerId: FlyerId, windowFrom: Instant, windowTo: Instant, fetchedAt: Instant)

trait FlyerLedger[F[_]] {

  /** Given today's listing, the flyers that need a full item fetch. Also
    * upserts flyer rows so first_seen_at/last_seen_at advance even for skips.
    */
  def selectToFetch(listing: List[Flyer], now: Instant): F[List[Flyer]]

  def markFetched(flyerId: FlyerId, window: (Instant, Instant), rawId: RawResponseId): F[Unit]

  def lastSeenAt(flyerId: FlyerId): F[Option[Instant]]
}

object FlyerLedger {

  /** The selection rule, pinned (03.4) and pure: fetch iff never fetched, OR
    * the validity window changed (re-issued flyer), OR the recorded fetch is
    * older than maxAge.
    */
  def needsFetch(flyer: Flyer, recorded: Option[LedgerEntry], now: Instant, maxAge: FiniteDuration): Boolean =
    recorded match {
      case None => true
      case Some(entry) =>
        val windowChanged = entry.windowFrom != flyer.validFrom || entry.windowTo != flyer.validTo
        val stale         = entry.fetchedAt.plusMillis(maxAge.toMillis).isBefore(now)
        windowChanged || stale
    }
}

final class DoobieFlyerLedger[F[_]: MonadCancelThrow](
    xa: Transactor[F],
    maxAge: FiniteDuration = 7.days,
) extends FlyerLedger[F] {
  import Codecs._

  def selectToFetch(listing: List[Flyer], now: Instant): F[List[Flyer]] = {
    val program = for {
      _       <- listing.traverse_(upsertFlyer(_, now))
      entries <- ledgerEntries(listing.map(_.id))
    } yield listing.filter(f => FlyerLedger.needsFetch(f, entries.get(f.id), now, maxAge))
    program.transact(xa)
  }

  def markFetched(flyerId: FlyerId, window: (Instant, Instant), rawId: RawResponseId): F[Unit] =
    sql"""INSERT INTO flyer_fetch_ledger (flyer_id, window_from, window_to, fetched_at, raw_response_id)
          VALUES (${flyerId.value}, ${window._1}, ${window._2}, now(), ${rawId.value})
          ON CONFLICT (flyer_id) DO UPDATE SET
            window_from = EXCLUDED.window_from,
            window_to = EXCLUDED.window_to,
            fetched_at = EXCLUDED.fetched_at,
            raw_response_id = EXCLUDED.raw_response_id""".update.run.void.transact(xa)

  def lastSeenAt(flyerId: FlyerId): F[Option[Instant]] =
    sql"SELECT last_seen_at FROM flyer WHERE id = ${flyerId.value}"
      .query[Instant]
      .option
      .transact(xa)

  /** Seen timestamps advance regardless of fetch selection (03.4). */
  private def upsertFlyer(f: Flyer, now: Instant): ConnectionIO[Unit] =
    sql"""INSERT INTO flyer (id, merchant_id, name, valid_from, valid_to, postal_code, locale, first_seen_at, last_seen_at)
          VALUES (${f.id.value}, ${f.merchantId.value}, ${f.name}, ${f.validFrom}, ${f.validTo},
                  ${f.postalCode.canonical}, ${f.locale}, $now, $now)
          ON CONFLICT (id) DO UPDATE SET
            name = EXCLUDED.name,
            valid_from = EXCLUDED.valid_from,
            valid_to = EXCLUDED.valid_to,
            last_seen_at = EXCLUDED.last_seen_at""".update.run.void

  private def ledgerEntries(ids: List[FlyerId]): ConnectionIO[Map[FlyerId, LedgerEntry]] =
    ids match {
      case Nil => Map.empty[FlyerId, LedgerEntry].pure[ConnectionIO]
      case _ =>
        val idList = ids.map(_.value)
        (sql"""SELECT flyer_id, window_from, window_to, fetched_at FROM flyer_fetch_ledger WHERE """ ++
          Fragments.in(fr"flyer_id", cats.data.NonEmptyList.fromListUnsafe(idList)))
          .query[(Long, Instant, Instant, Instant)]
          .to[List]
          .map(_.map { case (id, from, to, at) => FlyerId(id) -> LedgerEntry(FlyerId(id), from, to, at) }.toMap)
    }
}
