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

  private def app(queries: RunQueries[IO]) = new Routes[IO](queries).routes.orNotFound

  private def stub(result: IO[Option[RunView]]): RunQueries[IO] = new RunQueries[IO] {
    def latest: IO[Option[RunView]] = result
  }

  private def get(queries: RunQueries[IO], path: String = "/v1/runs/latest"): Response[IO] =
    app(queries).run(Request[IO](Method.GET, Uri.unsafeFromString(path))).unsafeRunSync()

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
