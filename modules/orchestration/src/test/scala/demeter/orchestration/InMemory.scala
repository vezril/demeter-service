package demeter.orchestration

import java.time.Instant

import cats.effect.IO
import cats.effect.kernel.Ref
import cats.effect.unsafe.implicits.global
import cats.syntax.all._
import demeter.alerting.{Alert, AlertKey, AlertLedger, AlertRecord, AlertSink, SinkName}
import demeter.foundations._
import demeter.persistence._

/** In-memory doubles for the 08.1 end-to-end suite: the pipeline is exercised
  * with zero network and zero database, so the run's control flow (idempotency,
  * fan-out bounds, failure isolation) is what's under test.
  */
object InMemory {

  final class MemRawStore(val rows: Ref[IO, Vector[(RawResponse, ResponseKind)]]) extends RawResponseStore[IO] {
    def put(
        raw: RawResponse,
        source: SourceName,
        kind: ResponseKind,
        postal: PostalCode,
        locale: Locale,
    ): IO[Either[DealWatchError, RawResponseId]] =
      rows.modify(rs => (rs :+ ((raw, kind)), Right(RawResponseId(rs.size.toLong + 1))))

    def get(id: RawResponseId): IO[Either[DealWatchError, RawResponse]] =
      rows.get.map(rs =>
        rs.lift(id.value.toInt - 1).map(_._1).toRight(DealWatchError.StoreConflict("raw_response", id.value.toString))
      )

    def stream(source: SourceName, kind: ResponseKind): fs2.Stream[IO, (RawResponseId, RawResponse)] =
      fs2.Stream.evalSeq(rows.get.map(_.zipWithIndex.collect {
        case ((raw, k), i) if k == kind => (RawResponseId(i.toLong + 1), raw)
      }.toList))
  }

  object MemRawStore {
    def create(): MemRawStore = new MemRawStore(Ref.of[IO, Vector[(RawResponse, ResponseKind)]](Vector.empty).unsafeRunSync())
  }

  /** Honours the same (product_key, flyer_id, observed_at) uniqueness the real
    * store enforces — that key IS the idempotency guarantee under test.
    */
  final class MemObservationStore(
      val saved: Ref[IO, Vector[PriceObservation]],
      val merchants: Ref[IO, Map[MerchantId, String]],
      failWith: Option[DealWatchError] = None,
  ) extends ObservationStore[IO] {

    private def key(o: PriceObservation) = (o.productKey, o.flyerId, o.observedAt)

    def upsertMerchants(ms: List[Merchant]): IO[Either[DealWatchError, Unit]] =
      merchants.update(_ ++ ms.map(m => m.id -> m.name)).as(Right(()))

    def save(obs: PriceObservation, rawId: RawResponseId): IO[Either[DealWatchError, SaveOutcome]] =
      failWith match {
        case Some(e) => IO.pure(Left(e))
        case None =>
          saved.modify { existing =>
            if (existing.exists(o => key(o) == key(obs))) (existing, Right(SaveOutcome.SkippedDuplicate))
            else (existing :+ obs, Right(SaveOutcome.Inserted))
          }
      }

    def saveAll(obs: List[PriceObservation], rawId: RawResponseId): IO[Either[DealWatchError, SaveReport]] =
      failWith match {
        case Some(e) => IO.pure(Left(e))
        case None =>
          obs.traverse(save(_, rawId)).map { outcomes =>
            Right(
              SaveReport(
                inserted = outcomes.count(_ == Right(SaveOutcome.Inserted)),
                skippedDuplicate = outcomes.count(_ == Right(SaveOutcome.SkippedDuplicate)),
                failed = 0,
              )
            )
          }
      }

    def observationsFor(k: ProductKey, since: Instant): IO[List[PriceObservation]] =
      saved.get.map(_.filter(o => o.productKey == k && !o.observedAt.isBefore(since)).sortBy(_.observedAt).reverse.toList)

    def currentObservations(activeAt: Instant): fs2.Stream[IO, PriceObservation] =
      fs2.Stream.evalSeq(saved.get.map(_.filter(o => !o.validFrom.isAfter(activeAt) && !o.validTo.isBefore(activeAt)).toList))

    def currentObservationsFor(merchant: MerchantId, activeAt: Instant): fs2.Stream[IO, PriceObservation] =
      currentObservations(activeAt).filter(_.merchantId == merchant)
  }

  object MemObservationStore {
    def create(failWith: Option[DealWatchError] = None): MemObservationStore =
      new MemObservationStore(
        Ref.of[IO, Vector[PriceObservation]](Vector.empty).unsafeRunSync(),
        Ref.of[IO, Map[MerchantId, String]](Map.empty).unsafeRunSync(),
        failWith,
      )
  }

  final class MemLedger(val fetched: Ref[IO, Map[FlyerId, (Instant, Instant)]]) extends FlyerLedger[IO] {
    def selectToFetch(listing: List[Flyer], now: Instant): IO[List[Flyer]] =
      fetched.get.map(seen => listing.filter(f => !seen.get(f.id).contains((f.validFrom, f.validTo))))

    def markFetched(flyerId: FlyerId, window: (Instant, Instant), rawId: RawResponseId): IO[Unit] =
      fetched.update(_ + (flyerId -> window))

    def lastSeenAt(flyerId: FlyerId): IO[Option[Instant]] = IO.pure(None)
  }

  object MemLedger {
    def create(): MemLedger = new MemLedger(Ref.of[IO, Map[FlyerId, (Instant, Instant)]](Map.empty).unsafeRunSync())
  }

  final class MemSink(val delivered: Ref[IO, Vector[Alert]], fail: Boolean = false) extends AlertSink[IO] {
    def name: SinkName = SinkName("memory")
    def deliver(alert: Alert): IO[Either[DealWatchError, Unit]] =
      if (fail) IO.pure(Left(DealWatchError.HttpStatus(503, "memory")))
      else delivered.update(_ :+ alert).as(Right(()))
  }

  object MemSink {
    def create(fail: Boolean = false): MemSink =
      new MemSink(Ref.of[IO, Vector[Alert]](Vector.empty).unsafeRunSync(), fail)
  }

  /** Survives across DailyRun instances in a test, the way the real table
    * survives across restarts.
    */
  final class MemAlertLedger(val entries: Ref[IO, Map[AlertKey, AlertRecord]]) extends AlertLedger[IO] {
    def openAt(now: Instant): IO[Map[AlertKey, AlertRecord]] =
      entries.get.map(_.filter { case (k, _) => !k.windowTo.isBefore(now) })

    def record(entry: AlertRecord): IO[Either[DealWatchError, Unit]] =
      entries.update(_ + (entry.key -> entry)).as(Right(()))

    def prune(cutoff: Instant): IO[Int] =
      entries.modify { es =>
        val keep = es.filter { case (k, _) => !k.windowTo.isBefore(cutoff) }
        (keep, es.size - keep.size)
      }
  }

  object MemAlertLedger {
    def create(): MemAlertLedger =
      new MemAlertLedger(Ref.of[IO, Map[AlertKey, AlertRecord]](Map.empty).unsafeRunSync())
  }
}
