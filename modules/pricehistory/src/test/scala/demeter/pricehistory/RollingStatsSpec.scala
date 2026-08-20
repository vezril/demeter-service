package demeter.pricehistory

import java.time.{Duration, Instant}

import demeter.foundations._
import org.scalacheck.Gen
import org.scalatest.funsuite.AnyFunSuite
import org.scalatestplus.scalacheck.ScalaCheckPropertyChecks

/** Spec 07.2 — rolling weighted price statistics. Tags: @pure (+ @property). */
final class RollingStatsSpec extends AnyFunSuite with ScalaCheckPropertyChecks {

  private val now = Instant.parse("2026-07-26T12:00:00Z")
  private val key = ProductKey("v1:k")

  private def point(
      cents: Option[Long],
      weeksAgo: Int = 1,
      basis: PriceBasis = PriceBasis.ScalarPrice,
      confidence: Confidence = Confidence.High,
      provenance: Provenance = Provenance.FirstParty,
  ): HistoryPoint = {
    val at = now.minus(Duration.ofDays(weeksAgo.toLong * 7))
    HistoryPoint(
      PriceObservation(
        productKey = key,
        merchantId = MerchantId(100),
        flyerId = FlyerId(900L),
        observedAt = at,
        name = BilingualText.enOnly("milk"),
        rawName = "milk",
        effectivePrice = cents.map(Money.cents(_)),
        priceBasis = if (cents.isEmpty) PriceBasis.PercentOffUnknown else basis,
        originalPrice = None,
        size = None,
        unitPrice = None,
        saleText = None,
        validFrom = at,
        validTo = at.plus(Duration.ofDays(7)),
        confidence = confidence,
      ),
      provenance,
    )
  }

  private val wideWindow = Duration.ofDays(365)

  test("median and min are computed over priced observations only") {
    val points = List(point(Some(250L)), point(Some(299L)), point(Some(349L)), point(None))
    val stats  = RollingStats.rollingStats(key, points, wideWindow, now)
    assert(stats.min.contains(Money.cents(250)))
    assert(stats.max.contains(Money.cents(349)))
    assert(stats.pricedN == 3)
    assert(stats.n == 4) // the promo is counted for context...
    assert(stats.weightedMedian.contains(Money.cents(299))) // ...but does not move the median
  }

  test("higher-confidence observations weigh more in the median") {
    val points = List(
      point(Some(200L), confidence = Confidence.Low, basis = PriceBasis.ParsedFromText),
      point(Some(300L), confidence = Confidence.High),
      point(Some(300L), confidence = Confidence.High),
    )
    val weighted = RollingStats.rollingStats(key, points, wideWindow, now).weightedMedian.get
    assert(weighted == Money.cents(300))
  }

  test("only observations within the window are included") {
    val points = List(point(Some(250L), weeksAgo = 1), point(Some(999L), weeksAgo = 12))
    val stats  = RollingStats.rollingStats(key, points, Duration.ofDays(8 * 7), now)
    assert(stats.pricedN == 1)
    assert(stats.max.contains(Money.cents(250)))
  }

  test("an empty or all-price-absent history yields no numeric stats") {
    val stats = RollingStats.rollingStats(key, List(point(None), point(None)), wideWindow, now)
    assert(stats.weightedMedian.isEmpty && stats.min.isEmpty && stats.max.isEmpty)
    assert(stats.n == 2)
    assert(stats.pricedN == 0)

    val empty = RollingStats.rollingStats(key, Nil, wideWindow, now)
    assert(empty.n == 0 && empty.weightedMedian.isEmpty)
  }

  test("weights are pinned: scalar/High counts full, parsed/Low counts least") {
    assert(RollingStats.weightOf(point(Some(100L))) == 1.0)
    assert(RollingStats.weightOf(point(Some(100L), basis = PriceBasis.MultiBuyUnit, confidence = Confidence.Medium)) < 1.0)
    val low   = RollingStats.weightOf(point(Some(100L), basis = PriceBasis.ParsedFromText, confidence = Confidence.Low))
    val multi = RollingStats.weightOf(point(Some(100L), basis = PriceBasis.MultiBuyUnit, confidence = Confidence.Medium))
    assert(low < multi)
  }

  test("Hammer provenance weighs less than first-party, fuzzy Hammer least") {
    val firstParty = RollingStats.weightOf(point(Some(100L)))
    val hammer     = RollingStats.weightOf(point(Some(100L), provenance = Provenance.Hammer))
    val fuzzy      = RollingStats.weightOf(point(Some(100L), provenance = Provenance.HammerFuzzy))
    assert(firstParty > hammer && hammer > fuzzy)
  }

  test("min <= weighted median <= max whenever stats exist (property)") {
    val genPoint = Gen.chooseNum(1L, 10000L).map(c => point(Some(c)))
    forAll(Gen.nonEmptyListOf(genPoint)) { points =>
      val stats = RollingStats.rollingStats(key, points, wideWindow, now)
      (stats.min, stats.weightedMedian, stats.max) match {
        case (Some(lo), Some(mid), Some(hi)) =>
          assert(lo.cents <= mid.cents && mid.cents <= hi.cents)
        case other => fail(s"expected full stats, got $other")
      }
    }
  }

  test("lastSeen reflects the most recent observation in the window") {
    val points = List(point(Some(250L), weeksAgo = 3), point(Some(310L), weeksAgo = 1))
    assert(RollingStats.rollingStats(key, points, wideWindow, now).lastSeen.contains(Money.cents(310)))
  }
}
