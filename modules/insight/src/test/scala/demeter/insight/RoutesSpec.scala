package demeter.insight

import java.time.Instant

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import io.circe.Json
import io.circe.parser.parse
import org.http4s._
import org.http4s.implicits._
import org.scalatest.funsuite.AnyFunSuite

/** Spec insight-api — the read-only surface. Tags: @pure. */
final class RoutesSpec extends AnyFunSuite {

  private val sample = RunView(
    id = 7L,
    startedAt = Instant.parse("2026-08-26T10:00:00Z"),
    finishedAt = Instant.parse("2026-08-26T10:39:05Z"),
    elapsedSeconds = Some(2345L),
    flyers = FlyerCounts(156, 156, 153, 3),
    items = ItemCounts(21439, 37),
    observations = ObservationCounts(19538, 1901),
    alerts = AlertCounts(
      matched = 272,
      delivered = 10,
      suppressed = 262,
      suppressedByReason = Map("above max price" -> 160, "not a sale" -> 89, "already alerted this window" -> 2),
      audience = Some(1),
    ),
    degradedSources = List("pcexpress"),
    failures = List("Timeout(https://backflipp.wishabi.com/flipp/flyers)"),
    partial = false,
  )

  private val emptyHistory: HistoryQueries[IO] = (key, window) =>
    IO.pure(
      HistoryView(key.value, window.toDays, Nil, StatsView(0, 0, None, None, None, None), None)
    )

  private val noWatches: WatchQueries[IO] = new WatchQueries[IO] {
    def watches: IO[List[WatchView]]            = IO.pure(Nil)
    def alerts(limit: Int): IO[List[AlertView]] = IO.pure(Nil)
  }

  private def app(
      queries: RunQueries[IO],
      hist: HistoryQueries[IO] = emptyHistory,
      w: WatchQueries[IO] = noWatches,
  ) = new Routes[IO](queries, hist, w).routes.orNotFound

  private def stub(result: IO[Option[RunView]], up: Boolean = true): RunQueries[IO] = new RunQueries[IO] {
    def latest: IO[Option[RunView]] = result
    def reachable: IO[Boolean]      = IO.pure(up)
  }

  private def get(
      queries: RunQueries[IO],
      path: String = "/v1/runs/latest",
      hist: HistoryQueries[IO] = emptyHistory,
      w: WatchQueries[IO] = noWatches,
  ): Response[IO] =
    app(queries, hist, w).run(Request[IO](Method.GET, Uri.unsafeFromString(path))).unsafeRunSync()

  private def bodyJson(r: Response[IO]): Json =
    parse(r.bodyText.compile.string.unsafeRunSync()).getOrElse(Json.Null)

  test("the latest run is returned as JSON") {
    val response = get(stub(IO.pure(Some(sample))))
    assert(response.status == Status.Ok)
    val c = bodyJson(response).hcursor
    assert(c.get[Long]("id").contains(7L))
    assert(c.downField("flyers").get[Int]("listed").contains(156))
  }

  test("suppression is reported per reason, not as a total") {
    // The field the whole endpoint exists for: a bare count cannot distinguish a
    // price ceiling that is too tight from an empty history from having already
    // told you.
    val reasons = bodyJson(get(stub(IO.pure(Some(sample))))).hcursor
      .downField("alerts")
      .get[Map[String, Int]]("suppressedByReason")
    assert(reasons.exists(_("above max price") == 160))
  }

  test("a delivery shortfall is computed, not left to the reader") {
    // 272 matched, 10 delivered, 262 suppressed reconciles to zero. A non-zero
    // figure means deliveries were attempted and failed -- the shortfall that
    // hid successful publishes being recorded as transport failures.
    val ok = bodyJson(get(stub(IO.pure(Some(sample))))).hcursor.downField("alerts").get[Int]("unaccounted")
    assert(ok.contains(0))

    val short = sample.copy(alerts = sample.alerts.copy(delivered = 0, suppressed = 260))
    val gap   = bodyJson(get(stub(IO.pure(Some(short))))).hcursor.downField("alerts").get[Int]("unaccounted")
    assert(gap.contains(12), "the 12 matches that neither alerted nor were suppressed must be visible")
  }

  test("an unknown audience serialises as null, never as zero") {
    // "could not ask" read as "nobody listening" would be a false alarm.
    val unknown = sample.copy(alerts = sample.alerts.copy(audience = None))
    val field   = bodyJson(get(stub(IO.pure(Some(unknown))))).hcursor.downField("alerts").downField("audience")
    assert(field.focus.contains(Json.Null))
  }

  test("a zero audience serialises as zero") {
    val none  = sample.copy(alerts = sample.alerts.copy(audience = Some(0)))
    val field = bodyJson(get(stub(IO.pure(Some(none))))).hcursor.downField("alerts").get[Int]("audience")
    assert(field.contains(0))
  }

  test("the decode failure rate is served, not recomputed by clients") {
    val rate = bodyJson(get(stub(IO.pure(Some(sample))))).hcursor.downField("items").get[Double]("decodeFailureRate")
    assert(rate.exists(r => r > 0.0017 && r < 0.0018))
  }

  test("no runs yet is 404, distinguishable from a run with no alerts") {
    assert(get(stub(IO.pure(None))).status == Status.NotFound)
  }

  test("a database outage is 503, never an empty result") {
    // An outage rendering as "no data" is how a broken view looks healthy.
    val response = get(stub(IO.raiseError(new java.sql.SQLException("connection refused"))))
    assert(response.status == Status.ServiceUnavailable)
  }

  test("the root serves an index, so a bookmark does not 404") {
    // Deliberately not a redirect to /v1/runs/latest: that legitimately answers
    // 404 until the first daily run, so a new deployment's very first visitor
    // would land on an error.
    val response = get(stub(IO.pure(None)), "/")
    assert(response.status == Status.Ok)
    val c = bodyJson(response).hcursor
    assert(c.get[String]("service").contains("demeter-insight"))
    assert(c.downField("endpoints").keys.exists(_.exists(_.contains("/v1/runs/latest"))))
  }

  test("the index works before any run exists") {
    // The case the peer session actually hit: routing proven, data absent.
    assert(get(stub(IO.pure(None)), "/").status == Status.Ok)
  }

  test("health is UP when the database answers, even with no runs yet") {
    // The bug this exists for: probing the data endpoint conflates "can serve"
    // with "has data". Before the first daily run that returns 404, a pod
    // probing it never becomes Ready, and a working service is held out of
    // rotation until tomorrow morning.
    val response = get(stub(IO.pure(None)), "/health")
    assert(response.status == Status.Ok)
    assert(bodyJson(response).hcursor.get[String]("status").contains("UP"))
  }

  test("health is 503 when the database does not answer") {
    val response = get(stub(IO.pure(None), up = false), "/health")
    assert(response.status == Status.ServiceUnavailable)
  }

  // --- product history ---

  test("a product with no observations is 200 with an empty series, not 404") {
    // "No observations for this product" is a data answer. Returning 404 would
    // teach a client that it means both "unknown key" and "nothing yet".
    val response = get(stub(IO.pure(None)), "/v1/products/iga%7Cmilk-4l/history")
    assert(response.status == Status.Ok)
    assert(bodyJson(response).hcursor.downField("points").values.exists(_.isEmpty))
  }

  test("the window defaults to the 8 weeks the verdict itself uses") {
    // So the chart and the alerts are describing the same period.
    val days = bodyJson(get(stub(IO.pure(None)), "/v1/products/k/history")).hcursor.get[Long]("windowDays")
    assert(days.contains(56L))
  }

  test("an explicit window is honoured") {
    val days = bodyJson(get(stub(IO.pure(None)), "/v1/products/k/history?days=14")).hcursor.get[Long]("windowDays")
    assert(days.contains(14L))
  }

  test("a nonsense window is clamped, not rejected") {
    // A bad number here is a typo, not an attack.
    val huge = bodyJson(get(stub(IO.pure(None)), "/v1/products/k/history?days=99999")).hcursor.get[Long]("windowDays")
    assert(huge.contains(365L))
    val zero = bodyJson(get(stub(IO.pure(None)), "/v1/products/k/history?days=0")).hcursor.get[Long]("windowDays")
    assert(zero.contains(1L))
  }

  test("a database outage on history is 503, never an empty series") {
    val failing: HistoryQueries[IO] = (_, _) => IO.raiseError(new java.sql.SQLException("refused"))
    assert(get(stub(IO.pure(None)), "/v1/products/k/history", failing).status == Status.ServiceUnavailable)
  }

  // --- watches and alerts ---

  private val oneWatch: WatchQueries[IO] = new WatchQueries[IO] {
    def watches: IO[List[WatchView]] = IO.pure(
      List(
        WatchView(
          "butter",
          "Butter",
          List("butter", "beurre"),
          List("peanut", "arachide"),
          Nil,
          Some(600L),
          requireSale = false,
          None,
          active = true,
          alertsSent = 4,
          lastAlertedAt = Some(Instant.parse("2026-08-25T13:00:00Z")),
        )
      )
    )
    def alerts(limit: Int): IO[List[AlertView]] = IO.pure(
      List.fill(math.min(limit, 3))(
        AlertView(
          "butter",
          Some("Butter"),
          "iga|beurre",
          Some(4001),
          Some("IGA"),
          Some("Beurre Lactantia"),
          Some(499L),
          Instant.parse("2026-08-25T13:00:00Z"),
          Instant.parse("2026-08-20T04:00:00Z"),
          Instant.parse("2026-08-27T04:00:00Z"),
        )
      )
    )
  }

  test("watches carry their exclusion terms, which is how a noisy one gets tamed") {
    val json  = bodyJson(get(stub(IO.pure(None)), "/v1/watches", w = oneWatch))
    val first = json.hcursor.downArray
    assert(first.get[List[String]]("excludeTerms").exists(_.contains("peanut")))
    assert(first.get[Int]("alertsSent").contains(4))
  }

  test("no watches configured is an empty list, not an error") {
    val response = get(stub(IO.pure(None)), "/v1/watches")
    assert(response.status == Status.Ok)
    assert(bodyJson(response).asArray.exists(_.isEmpty))
  }

  test("alerts resolve a merchant, since ProductKey is merchant-scoped") {
    val first = bodyJson(get(stub(IO.pure(None)), "/v1/alerts", w = oneWatch)).hcursor.downArray
    assert(first.get[String]("merchantName").contains("IGA"))
    assert(first.get[Long]("alertedCents").contains(499L))
  }

  test("the alert limit is clamped rather than trusted") {
    // Same reasoning as the history window: a bad number is a typo, and an
    // unbounded limit is a way to ask for the whole ledger by accident.
    assert(bodyJson(get(stub(IO.pure(None)), "/v1/alerts?limit=99999", w = oneWatch)).asArray.exists(_.size == 3))
    assert(get(stub(IO.pure(None)), "/v1/alerts?limit=0", w = oneWatch).status == Status.Ok)
  }

  test("a database outage on watches is 503, not an empty list") {
    // An empty list would read as "you have no watches", which is a different
    // and much more alarming statement than "I cannot reach the database".
    val failing: WatchQueries[IO] = new WatchQueries[IO] {
      def watches: IO[List[WatchView]]            = IO.raiseError(new java.sql.SQLException("refused"))
      def alerts(limit: Int): IO[List[AlertView]] = IO.raiseError(new java.sql.SQLException("refused"))
    }
    assert(get(stub(IO.pure(None)), "/v1/watches", w = failing).status == Status.ServiceUnavailable)
    assert(get(stub(IO.pure(None)), "/v1/alerts", w = failing).status == Status.ServiceUnavailable)
  }

  test("every route is a GET") {
    // A design constraint, not a description of today: a write path drags in
    // authentication this tool deliberately does not have.
    val methods = List(Method.POST, Method.PUT, Method.PATCH, Method.DELETE)
    for (m <- methods) {
      val response = app(stub(IO.pure(Some(sample))))
        .run(Request[IO](m, uri"/v1/runs/latest"))
        .unsafeRunSync()
      assert(response.status == Status.NotFound, s"$m must not be routed")
    }
  }
}
