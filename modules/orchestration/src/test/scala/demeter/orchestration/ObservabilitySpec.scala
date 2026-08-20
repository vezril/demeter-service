package demeter.orchestration

import scala.concurrent.duration._

import demeter.foundations.{DealWatchError, SourceName}
import org.scalatest.funsuite.AnyFunSuite

/** Specs 08.2 / 08.3 — degradation policy and drift detection. Tags: @pure. */
final class ObservabilitySpec extends AnyFunSuite {

  private val flipp = SourceName("flipp")
  private val url   = "https://backflipp.wishabi.com/flipp/flyers"

  // --- 08.3 run report ---

  test("a run emits a complete report") {
    val report = RunReport(
      flyersListed = 100,
      flyersSelected = 12,
      flyersFetched = 11,
      flyersFailed = 1,
      itemsParsed = 773,
      itemsDropped = 3,
      observationsInserted = 700,
      observationsSkipped = 73,
      matches = 8,
      alertsDelivered = 2,
      alertsSuppressed = 6,
      degraded = List(DegradedSource(SourceName("pcexpress"), DealWatchError.HttpStatus(401, "x"))),
      elapsed = Some(42.seconds),
    )
    val text = Observability.prometheus(report)
    assert(text.contains("demeter_flyers_listed 100.0"))
    assert(text.contains("demeter_items_dropped 3.0"))
    assert(text.contains("demeter_alerts_delivered 2.0"))
    assert(text.contains("demeter_sources_degraded 1.0"))
    assert(report.isDegraded(SourceName("pcexpress")))
  }

  test("suppression is reported by reason, because silence has several causes") {
    // "398 suppressed" cannot distinguish a price ceiling that is too tight from
    // an empty history from having already told you — all three look identical
    val report = RunReport(matches = 398)
      .withSuppression("above max price")
      .withSuppression("above max price")
      .withSuppression("not a sale")
      .withSuppression("already alerted this window")

    assert(report.alertsSuppressed == 4)
    assert(report.suppressedByReason == Map("above max price" -> 2, "not a sale" -> 1, "already alerted this window" -> 1))

    val text = Observability.prometheus(report)
    assert(text.contains("""demeter_alerts_suppressed_reason{reason="above max price"} 2.0"""))
    assert(text.contains("demeter_alerts_suppressed 4.0"))
    assert(text.contains("demeter_run_seconds"), "the rest of the exposition survives")
  }

  test("a report with no suppressions emits clean metrics") {
    val text = Observability.prometheus(RunReport(matches = 3, alertsDelivered = 3))
    assert(!text.contains("suppressed_reason"))
    assert(text.contains("demeter_alerts_suppressed 0.0"))
    assert(text.linesIterator.forall(_.startsWith("demeter_")), "no stray blank lines in the exposition")
  }

  test("a decode-failure spike raises a drift alarm naming the source") {
    val report = RunReport(flyersListed = 10, itemsParsed = 900, itemsDropped = 100) // 10% > 5% threshold
    val alarms = Observability.alarms(report, flipp)
    assert(alarms.exists(_.isInstanceOf[DriftAlarm.DecodeDrift]))
    assert(alarms.head.message.contains("flipp"))
  }

  test("a decode rate under the threshold raises nothing") {
    val report = RunReport(flyersListed = 10, itemsParsed = 997, itemsDropped = 3)
    assert(Observability.alarms(report, flipp).isEmpty)
  }

  test("a decode drift alarm is distinguishable from a transport outage") {
    val outage = RunReport(
      flyersListed = 0,
      degraded = List(DegradedSource(flipp, DealWatchError.Timeout(url))),
      failures = List(DealWatchError.Timeout(url)),
    )
    val alarms = Observability.alarms(outage, flipp)
    // a transport outage must NOT masquerade as schema drift or a zero-result anomaly
    assert(!alarms.exists(_.isInstanceOf[DriftAlarm.DecodeDrift]))
    assert(!alarms.exists(_.isInstanceOf[DriftAlarm.ZeroResult]))
  }

  test("a zero-result anomaly is flagged when nothing came back and nothing errored") {
    val silent = RunReport(flyersListed = 0)
    assert(Observability.alarms(silent, flipp).exists(_.isInstanceOf[DriftAlarm.ZeroResult]))
  }

  test("a genuinely empty item search does not raise a zero-result anomaly") {
    // flyers listed fine; the search simply matched nothing (01.3: empty is a data signal)
    val report = RunReport(flyersListed = 121, itemsParsed = 0, matches = 0)
    assert(!Observability.alarms(report, flipp).exists(_.isInstanceOf[DriftAlarm.ZeroResult]))
  }

  test("an alert-volume collapse across a normally-active watchlist is flagged") {
    val report = RunReport(flyersListed = 100, matches = 9, alertsDelivered = 0, alertsSuppressed = 0)
    assert(Observability.alarms(report, flipp).exists(_.isInstanceOf[DriftAlarm.AlertVolumeCollapse]))
  }

  test("suppressed-but-considered alerts are not a collapse") {
    val report = RunReport(flyersListed = 100, matches = 9, alertsDelivered = 0, alertsSuppressed = 9)
    assert(!Observability.alarms(report, flipp).exists(_.isInstanceOf[DriftAlarm.AlertVolumeCollapse]))
  }

  // --- 08.2 degradation ---

  test("a Flipp bot wall with a fallback switches source and alerts the operator") {
    val botWall = DealWatchError.BotWall(url, "cf-chl-bypass")
    assert(DegradationPolicy.decide(botWall, fallbackAvailable = true, essential = true) == Degradation.UseFallbackAndAlert)
    assert(DegradationPolicy.needsOperatorAlert(botWall, essential = true))
    assert(!botWall.retriable) // never retried in a loop
  }

  test("a Flipp bot wall with no fallback yields a clean partial run plus an operator alert") {
    val botWall = DealWatchError.BotWall(url, "cf-chl")
    assert(DegradationPolicy.decide(botWall, fallbackAvailable = false, essential = true) == Degradation.PartialRunAndAlert)
    assert(DegradationPolicy.needsOperatorAlert(botWall, essential = true))
  }

  test("an enrichment outage drops to history-only verdicts, never blocking alerts") {
    val down = DealWatchError.HttpStatus(503, "https://api.pcexpress.ca")
    assert(DegradationPolicy.decide(down, fallbackAvailable = false, essential = false) == Degradation.ContinueWithout)
  }

  test("a persistent store outage fails the run loudly") {
    val down = DealWatchError.StoreUnavailable("connection refused")
    assert(DegradationPolicy.decide(down, fallbackAvailable = true, essential = true) == Degradation.FailRun)
    assert(DegradationPolicy.needsOperatorAlert(down, essential = true))
  }

  test("repeated 5xx past the retry budget degrades the source and continues") {
    val down = DealWatchError.HttpStatus(503, url)
    assert(DegradationPolicy.decide(down, fallbackAvailable = false, essential = true) == Degradation.PartialRunAndAlert)
  }
}
