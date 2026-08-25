package demeter.orchestration

import java.time.Instant

import cats.effect.kernel.MonadCancelThrow
import cats.syntax.all._
import doobie._
import doobie.implicits._
import doobie.postgres.implicits._
import io.circe.syntax._
import io.circe.parser.parse

import demeter.foundations.DealWatchError

/** Spec 08.3 — the run report, made durable.
  *
  * The report used to exist only as a log line, which made "was yesterday's run
  * healthy?" answerable only by whoever still had the pod's stdout. It is also
  * how a delivery that succeeded while being counted as a failure stayed
  * invisible: catching it needed the log and the broker's own counter side by
  * side, by hand.
  *
  * Written once at the end of a run and never updated. A write failure is
  * returned as a value and must not fail the run: the run already happened, and
  * losing the bookkeeping is a far smaller loss than losing the fetch.
  */
trait RunReportStore[F[_]] {
  def save(report: RunReport, startedAt: Instant, finishedAt: Instant): F[Either[DealWatchError, Unit]]
  def latest: F[Option[PersistedRun]]
}

/** A report as stored, with the times the in-memory RunReport does not carry. */
final case class PersistedRun(
    id: Long,
    startedAt: Instant,
    finishedAt: Instant,
    report: RunReport,
)

final class DoobieRunReportStore[F[_]: MonadCancelThrow](xa: Transactor[F]) extends RunReportStore[F] {

  def save(report: RunReport, startedAt: Instant, finishedAt: Instant): F[Either[DealWatchError, Unit]] = {
    val reasons                = report.suppressedByReason.asJson.noSpaces
    val degraded: List[String] = report.degraded.map(_.source.value)
    // DealWatchError has no message accessor; its case-class rendering is what
    // the run's own log lines already use.
    val failures: List[String] = report.failures.map(_.toString)
    sql"""INSERT INTO run_report (
            started_at, finished_at, elapsed_seconds,
            flyers_listed, flyers_selected, flyers_fetched, flyers_failed,
            items_parsed, items_dropped,
            observations_inserted, observations_skipped,
            matches, alerts_delivered, alerts_suppressed,
            suppressed_by_reason, alert_audience,
            degraded_sources, failures, partial
          ) VALUES (
            $startedAt, $finishedAt, ${report.elapsed.map(_.toSeconds)},
            ${report.flyersListed}, ${report.flyersSelected}, ${report.flyersFetched}, ${report.flyersFailed},
            ${report.itemsParsed}, ${report.itemsDropped},
            ${report.observationsInserted}, ${report.observationsSkipped},
            ${report.matches}, ${report.alertsDelivered}, ${report.alertsSuppressed},
            $reasons::jsonb, ${report.alertAudience},
            $degraded, $failures, ${report.partial}
          )""".update.run
      .transact(xa)
      .attempt
      .map(_.bimap(e => DealWatchError.StoreUnavailable(e.getMessage): DealWatchError, _ => ()))
  }

  def latest: F[Option[PersistedRun]] =
    sql"""SELECT id, started_at, finished_at, elapsed_seconds,
                 flyers_listed, flyers_selected, flyers_fetched, flyers_failed,
                 items_parsed, items_dropped,
                 observations_inserted, observations_skipped,
                 matches, alerts_delivered, alerts_suppressed,
                 suppressed_by_reason::text, alert_audience, partial
            FROM run_report
           ORDER BY finished_at DESC
           LIMIT 1"""
      .query[
        (
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
            Boolean,
        )
      ]
      .option
      .transact(xa)
      .map(_.map {
        case (
              id,
              started,
              finished,
              elapsed,
              fl,
              fs,
              ff,
              ffail,
              ip,
              idrop,
              oi,
              os,
              m,
              ad,
              asup,
              reasons,
              audience,
              partial,
            ) =>
          PersistedRun(
            id = id,
            startedAt = started,
            finishedAt = finished,
            report = RunReport(
              flyersListed = fl,
              flyersSelected = fs,
              flyersFetched = ff,
              flyersFailed = ffail,
              itemsParsed = ip,
              itemsDropped = idrop,
              observationsInserted = oi,
              observationsSkipped = os,
              matches = m,
              alertsDelivered = ad,
              alertsSuppressed = asup,
              suppressedByReason =
                parse(reasons).toOption.flatMap(_.as[Map[String, Int]].toOption).getOrElse(Map.empty),
              alertAudience = audience,
              elapsed = elapsed.map(scala.concurrent.duration.Duration(_, "seconds")).collect {
                case f: scala.concurrent.duration.FiniteDuration => f
              },
              partial = partial,
            ),
          )
      })
}
