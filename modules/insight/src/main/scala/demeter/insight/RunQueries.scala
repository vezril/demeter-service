package demeter.insight

import java.time.Instant

import cats.effect.kernel.MonadCancelThrow
import cats.syntax.all._
import doobie._
import doobie.implicits._
import doobie.postgres.implicits._
import io.circe.parser.parse

/** Reads over the demeter schema, owned by this service.
  *
  * The columns are named out in full rather than importing `orchestration`'s
  * store, so a schema change breaks this file at compile time instead of quietly
  * altering what an endpoint returns. That is the whole reason a second consumer
  * of someone else's schema is tolerable.
  *
  * Every query here is a SELECT. The role this connects as is granted nothing
  * else, so that is enforced by PostgreSQL rather than by this comment.
  */
trait RunQueries[F[_]] {
  def latest: F[Option[RunView]]

  /** Can this service answer at all? Distinct from "is there data": a database
    * that is reachable and empty is healthy, and conflating the two makes a
    * service that has simply never had a run look broken.
    */
  def reachable: F[Boolean]
}

final class DoobieRunQueries[F[_]: MonadCancelThrow](xa: Transactor[F]) extends RunQueries[F] {

  private type Row = (
      Long,
      Instant,
      Instant,
      Option[Long],
      Int,
      Int,
      Int,
      Int,
      Int,
      Int,
      Int,
      Int,
      Int,
      Int,
      Int,
      String,
      Option[Int],
      List[String],
      List[String],
      Boolean,
  )

  def reachable: F[Boolean] =
    sql"SELECT 1".query[Int].option.transact(xa).attempt.map(_.isRight)

  def latest: F[Option[RunView]] =
    sql"""SELECT id, started_at, finished_at, elapsed_seconds,
                 flyers_listed, flyers_selected, flyers_fetched, flyers_failed,
                 items_parsed, items_dropped,
                 observations_inserted, observations_skipped,
                 matches, alerts_delivered, alerts_suppressed,
                 suppressed_by_reason::text, alert_audience,
                 degraded_sources, failures, partial
            FROM run_report
           ORDER BY finished_at DESC
           LIMIT 1"""
      .query[Row]
      .option
      .transact(xa)
      .map(_.map(toView))

  private def toView(row: Row): RunView = {
    val (
      id,
      started,
      finished,
      elapsed,
      fListed,
      fSelected,
      fFetched,
      fFailed,
      iParsed,
      iDropped,
      oInserted,
      oSkipped,
      matched,
      delivered,
      suppressed,
      reasonsJson,
      audience,
      degraded,
      failures,
      partial,
    ) = row
    RunView(
      id = id,
      startedAt = started,
      finishedAt = finished,
      elapsedSeconds = elapsed,
      flyers = FlyerCounts(fListed, fSelected, fFetched, fFailed),
      items = ItemCounts(iParsed, iDropped),
      observations = ObservationCounts(oInserted, oSkipped),
      alerts = AlertCounts(
        matched = matched,
        delivered = delivered,
        suppressed = suppressed,
        // An unreadable map is reported as empty rather than failing the whole
        // response: one malformed column should not hide a run's counts.
        suppressedByReason = parse(reasonsJson).toOption.flatMap(_.as[Map[String, Int]].toOption).getOrElse(Map.empty),
        audience = audience,
      ),
      degradedSources = degraded,
      failures = failures,
      partial = partial,
    )
  }
}
