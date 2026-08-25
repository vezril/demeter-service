package demeter.orchestration

import scala.concurrent.duration.FiniteDuration

import demeter.foundations.{DealWatchError, SourceName}

/** Spec 08.3 — make the service's health legible.
  *
  * An undocumented-endpoint scraper fails SILENTLY by default: Flipp changes a
  * field, the decode-failure rate quietly climbs, and you notice weeks later
  * that alerts stopped. The run report plus the drift alarms turn that silent
  * decay into a visible signal.
  */
final case class DegradedSource(source: SourceName, reason: DealWatchError)

final case class RunReport(
    flyersListed: Int = 0,
    flyersSelected: Int = 0,
    flyersFetched: Int = 0,
    flyersFailed: Int = 0,
    itemsParsed: Int = 0,
    itemsDropped: Int = 0,
    observationsInserted: Int = 0,
    observationsSkipped: Int = 0,
    matches: Int = 0,
    alertsDelivered: Int = 0,
    alertsSuppressed: Int = 0,
    /** Why alerts were held back, per 05.1's reasons. A bare total cannot
      * distinguish "everything is above your price ceiling" from "we have no
      * history yet" from "you already heard about all of it" — three very
      * different situations that all look like silence.
      */
    suppressedByReason: Map[String, Int] = Map.empty,
    /** Consumers attached to the alert channel, when the sink can tell. None is
      * "unknown", which must not be read as zero (08.3).
      */
    alertAudience: Option[Int] = None,
    degraded: List[DegradedSource] = Nil,
    failures: List[DealWatchError] = Nil,
    elapsed: Option[FiniteDuration] = None,
    partial: Boolean = false,
) {
  def decodeFailureRate: Double =
    if (itemsParsed + itemsDropped == 0) 0.0 else itemsDropped.toDouble / (itemsParsed + itemsDropped)

  def isDegraded(source: SourceName): Boolean = degraded.exists(_.source == source)

  def withSuppression(reason: String): RunReport =
    copy(
      alertsSuppressed = alertsSuppressed + 1,
      suppressedByReason = suppressedByReason.updated(reason, suppressedByReason.getOrElse(reason, 0) + 1),
    )
}

sealed abstract class DriftAlarm(val message: String) extends Product with Serializable

object DriftAlarm {

  /** Schema drift: the decode-failure rate for a source crossed the threshold. */
  final case class DecodeDrift(source: SourceName, rate: Double, threshold: Double)
      extends DriftAlarm(
        f"$source%s decode-failure rate ${rate * 100}%.1f%% exceeds ${threshold * 100}%.1f%% — likely schema drift"
      )

  /** Silent breakage: a normally-productive source returned ~nothing, with no transport error. */
  final case class ZeroResult(source: SourceName, expectedAtLeast: Int)
      extends DriftAlarm(s"$source returned zero results with no transport error (usually >= $expectedAtLeast)")

  /** A normally-active watchlist suddenly delivering nothing. */
  final case class AlertVolumeCollapse(matches: Int)
      extends DriftAlarm(s"$matches matches produced zero alerts across a normally-active watchlist")

  /** Alerts published into a channel with nobody attached.
    *
    * The most convincing kind of silent failure: every delivery succeeds, the
    * run report is green, and no human is ever told. A broker accepts messages
    * whether or not anyone subscribes, and on the first real run this service
    * published ten alerts to a topic with zero subscriptions -- which the
    * report happily called ten delivered.
    */
  final case class NoAudience(delivered: Int)
      extends DriftAlarm(
        s"$delivered alerts were published to a channel with no subscribers — delivery succeeded and nobody was told"
      )
}

final case class DriftThresholds(
    decodeFailureRate: Double = 0.05,
    expectFlyersAtLeast: Int = 1,
    alertVolumeMinMatches: Int = 5,
)

object Observability {

  /** Drift alarms are computed from the report, so they're pure and testable.
    * A transport outage is deliberately NOT a decode-drift alarm: the two need
    * distinct responses (08.3).
    */
  def alarms(
      report: RunReport,
      source: SourceName,
      thresholds: DriftThresholds = DriftThresholds(),
  ): List[DriftAlarm] = {
    val decode =
      Option.when(
        report.itemsParsed + report.itemsDropped > 0 && report.decodeFailureRate > thresholds.decodeFailureRate
      )(
        DriftAlarm.DecodeDrift(source, report.decodeFailureRate, thresholds.decodeFailureRate)
      )

    // zero results only alarms when the source did NOT report a transport failure
    val zeroResult =
      Option.when(report.flyersListed == 0 && report.degraded.isEmpty && report.failures.isEmpty)(
        DriftAlarm.ZeroResult(source, thresholds.expectFlyersAtLeast)
      )

    val alertVolume =
      Option.when(
        report.matches >= thresholds.alertVolumeMinMatches && report.alertsDelivered == 0 && report.alertsSuppressed == 0
      )(
        DriftAlarm.AlertVolumeCollapse(report.matches)
      )

    // Only a DEFINITE zero alarms. A sink that cannot count its consumers
    // reports None, and an unknown audience is not evidence of an empty one.
    val noAudience =
      Option.when(report.alertsDelivered > 0 && report.alertAudience.contains(0))(
        DriftAlarm.NoAudience(report.alertsDelivered)
      )

    List(decode, zeroResult, alertVolume, noAudience).flatten
  }

  /** Prometheus text exposition, so Home Assistant or a small dashboard can chart it. */
  def prometheus(report: RunReport): String = {
    def metric(name: String, value: Double): String = s"demeter_$name $value"
    List(
      metric("flyers_listed", report.flyersListed.toDouble),
      metric("flyers_selected", report.flyersSelected.toDouble),
      metric("flyers_fetched", report.flyersFetched.toDouble),
      metric("flyers_failed", report.flyersFailed.toDouble),
      metric("items_parsed", report.itemsParsed.toDouble),
      metric("items_dropped", report.itemsDropped.toDouble),
      metric("observations_inserted", report.observationsInserted.toDouble),
      metric("observations_skipped", report.observationsSkipped.toDouble),
      metric("matches", report.matches.toDouble),
      metric("alerts_delivered", report.alertsDelivered.toDouble),
      metric("alerts_suppressed", report.alertsSuppressed.toDouble),
    ).mkString("\n") + report.suppressedByReason.toList.sorted.map { case (reason, n) =>
      "\n" + metric(s"""alerts_suppressed_reason{reason="$reason"}""", n.toDouble)
    }.mkString + List(
      "",
      metric("alert_audience", report.alertAudience.map(_.toDouble).getOrElse(-1.0)),
      metric("sources_degraded", report.degraded.size.toDouble),
      metric("decode_failure_rate", report.decodeFailureRate),
      metric("run_seconds", report.elapsed.map(_.toSeconds.toDouble).getOrElse(0.0)),
    ).mkString("\n")
  }
}
