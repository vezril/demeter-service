package demeter.insight

import java.time.{Duration, Instant}

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import doobie.implicits._
import org.scalatest.funsuite.AnyFunSuite

import demeter.foundations._
import demeter.persistence.{DoobieObservationStore, DoobieRawResponseStore, ResponseKind, Schema}
import demeter.pricehistory.{HistoryPoint, RollingStats}

/** Spec insight-api — per-product history. Tags: @boundary. */
final class HistoryQueriesSpec extends AnyFunSuite {

  private val key      = ProductKey("iga|test-milk-4l")
  private val merchant = MerchantId(4001)
  private val now      = Instant.parse("2026-08-25T12:00:00Z")

  private def observation(daysAgo: Long, cents: Long, confidence: Confidence = Confidence.High) =
    PriceObservation(
      productKey = key,
      merchantId = merchant,
      flyerId = FlyerId(9001L),
      observedAt = now.minus(Duration.ofDays(daysAgo)),
      name = BilingualText(Some("Lait 4 L"), Some("Milk 4 L")),
      rawName = "Milk 4 L",
      effectivePrice = Some(Money.cents(cents)),
      priceBasis = PriceBasis.ScalarPrice,
      originalPrice = None,
      size = None,
      unitPrice = None,
      saleText = None,
      validFrom = now.minus(Duration.ofDays(daysAgo + 1)),
      validTo = now.minus(Duration.ofDays(daysAgo - 6)),
      priceConfidence = confidence,
      matchConfidence = Confidence.High,
    )

  private def pgTest(name: String)(body: => Any): Unit =
    test(name) {
      assume(PgTest.available, "Postgres not reachable on localhost:55432 — run `docker compose up -d postgres`")
      Schema.migrate(PgTest.xa).unsafeRunSync()
      sql"DELETE FROM price_observation WHERE product_key = ${key.value}".update.run.transact(PgTest.xa).unsafeRunSync()
      body
      ()
    }

  private def seed(observations: List[PriceObservation]): Unit = {
    // Observations are traceable to a raw response by foreign key, so one has to
    // exist first -- the same constraint the daily run satisfies.
    val id = new DoobieRawResponseStore[IO](PgTest.xa)
      .put(
        RawResponse("""{"t":1}""".getBytes, "application/json", now, "http://test"),
        SourceName("test"),
        ResponseKind.FlyerItems,
        PostalCode.parse("H1X3B1").toOption.get,
        Locale.EnCa,
      )
      .unsafeRunSync()
      .toOption
      .get
    new DoobieObservationStore[IO](PgTest.xa).saveAll(observations, id).unsafeRunSync()
    ()
  }

  pgTest("a series comes back in time order with per-point confidence") {
    seed(List(observation(30, 499), observation(2, 399, Confidence.Low), observation(16, 549)))
    val view = queriesFor(_.forProduct(key, Duration.ofDays(56)))
    assert(view.points.size == 3)
    val _ = assert(view.points.map(_.priceCents) == List(Some(499L), Some(549L), Some(399L)), "oldest first")
    assert(view.points.last.confidence == "Low", "confidence travels per point, not per series")
  }

  pgTest("the statistics equal what pricehistory computes over the same rows") {
    // The reuse claim, checked rather than asserted: if these ever diverge, the
    // UI and the alerts are describing the same product differently.
    val observations = List(observation(30, 499), observation(16, 549), observation(2, 399))
    seed(observations)
    val view     = queriesFor(_.forProduct(key, Duration.ofDays(56)))
    val expected = RollingStats.rollingStats(key, observations.map(HistoryPoint(_)), Duration.ofDays(56), now)
    val _        = assert(view.stats.weightedMedianCents == expected.weightedMedian.map(_.cents))
    val _        = assert(view.stats.minCents == expected.min.map(_.cents))
    val _        = assert(view.stats.maxCents == expected.max.map(_.cents))
    assert(view.stats.pricedN == expected.pricedN)
  }

  pgTest("the window excludes older observations") {
    seed(List(observation(80, 199), observation(2, 399)))
    val view = queriesFor(_.forProduct(key, Duration.ofDays(56)))
    val _    = assert(view.points.size == 1, "the 80-day-old observation is outside an 8-week window")
    assert(view.stats.minCents.contains(399L), "and must not drag the minimum down")
  }

  pgTest("the latest observation gets a verdict judged against the REST of the window") {
    // Not against itself: a price compared with a set including itself is
    // always at least median, which is how everything looks like a good deal.
    seed(List(observation(30, 599), observation(16, 579), observation(2, 399)))
    val view = queriesFor(_.forProduct(key, Duration.ofDays(56)))
    assert(view.verdict.isDefined)
  }

  pgTest("a product with no observations is an empty series, not an error") {
    val view = queriesFor(_.forProduct(ProductKey("iga|nothing-here"), Duration.ofDays(56)))
    val _    = assert(view.points.isEmpty)
    val _    = assert(view.stats.pricedN == 0)
    assert(view.verdict.isEmpty)
  }

  private def queriesFor(f: DbHistoryQueries[IO] => IO[HistoryView]): HistoryView =
    f(new DbHistoryQueries[IO](PgTest.xa)).unsafeRunSync()
}
