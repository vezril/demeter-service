package demeter.pricehistory

import java.time.{Duration, Instant}

import demeter.foundations._
import org.scalatest.funsuite.AnyFunSuite

/** Spec 07.3 — deal quality verdict. Tags: @pure. */
final class DealScorerSpec extends AnyFunSuite {

  private val now    = Instant.parse("2026-07-26T12:00:00Z")
  private val key    = ProductKey("v1:k")
  private val window = Duration.ofDays(8 * 7) // 8 weeks

  private def obs(cents: Option[Long], original: Option[Long] = None): PriceObservation =
    PriceObservation(
      productKey = key,
      merchantId = MerchantId(100),
      flyerId = FlyerId(900L),
      observedAt = now,
      name = BilingualText.enOnly("milk"),
      rawName = "milk",
      effectivePrice = cents.map(Money.cents(_)),
      priceBasis = if (cents.isEmpty) PriceBasis.PercentOffUnknown else PriceBasis.ScalarPrice,
      originalPrice = original.map(Money.cents(_)),
      size = None,
      unitPrice = None,
      saleText = None,
      validFrom = now,
      validTo = now.plus(Duration.ofDays(7)),
      priceConfidence = Confidence.High,
      matchConfidence = Confidence.High,
    )

  private def stats(median: Option[Long], min: Option[Long], pricedN: Int): PriceStats =
    PriceStats(
      key = key,
      window = window,
      n = pricedN,
      pricedN = pricedN,
      weightedMedian = median.map(Money.cents(_)),
      min = min.map(Money.cents(_)),
      max = median.map(m => Money.cents(m + 100)),
      lastSeen = median.map(Money.cents(_)),
    )

  test("a price at the trailing minimum is best-ever with the window in weeks") {
    val verdict = DealScorer.scoreDeal(obs(Some(250L)), stats(Some(300L), Some(250L), pricedN = 8))
    assert(verdict == DealVerdict.BestEver(8))
    assert(verdict.phrase == "cheapest in 8 weeks")
  }

  test("a modest markdown below the median is below-usual") {
    val verdict = DealScorer.scoreDeal(obs(Some(255L)), stats(Some(300L), Some(200L), pricedN = 8))
    verdict match {
      case DealVerdict.BelowUsual(pct) => assert(pct == 15)
      case other                       => fail(s"expected BelowUsual, got $other")
    }
  }

  test("a sale priced at or above the usual is flagged as not a deal") {
    val verdict = DealScorer.scoreDeal(obs(Some(319L)), stats(Some(300L), Some(200L), pricedN = 8))
    assert(verdict == DealVerdict.AtOrAboveUsual)
    assert(!verdict.isNotableOrBetter)
  }

  test("thin history cannot support a NEGATIVE claim either") {
    // The failure this prevents: on a first run every product has one
    // observation, so every verdict came back "not actually a deal". Combined
    // with requireSale that suppresses every alert for weeks while the service
    // looks perfectly healthy.
    val verdict = DealScorer.scoreDeal(obs(Some(300L)), stats(Some(300L), Some(300L), pricedN = 1))
    assert(verdict == DealVerdict.Unknown, "one data point is not evidence of anything")
    assert(!verdict.isNotableOrBetter, "and Unknown still does not pass requireSale")
  }

  test("enrichment lets a thin history judge negatively, because the baseline is real") {
    // a retailer's actual regular price is a baseline no matter how little
    // history we hold
    val verdict = DealScorer.scoreDeal(
      obs(Some(999L)),
      stats(None, None, pricedN = 0),
      enrichment = Some(DealScorer.EnrichmentBaseline(Some(Money.cents(999)))),
    )
    assert(verdict == DealVerdict.AtOrAboveUsual)
  }

  test("once history is deep enough, a negative verdict is allowed again") {
    val verdict = DealScorer.scoreDeal(obs(Some(300L)), stats(Some(300L), Some(250L), pricedN = 8))
    assert(verdict == DealVerdict.AtOrAboveUsual)
  }

  test("thin history never yields an over-confident best-ever") {
    val verdict = DealScorer.scoreDeal(obs(Some(180L)), stats(Some(300L), Some(200L), pricedN = 2))
    assert(verdict == DealVerdict.Notable)
  }

  test("enrichment regular price overrides an inflated flyer regular") {
    // flyer claims regular 39.99, sale 29.99; enrichment says the real regular IS 29.99
    val verdict = DealScorer.scoreDeal(
      obs(Some(2999L), original = Some(3999L)),
      stats(None, None, pricedN = 0),
      enrichment = Some(DealScorer.EnrichmentBaseline(Some(Money.cents(2999)))),
    )
    assert(verdict == DealVerdict.AtOrAboveUsual)
    assert(DealScorer.percentBelow(Money.cents(2999), Money.cents(2999)) == 0)
  }

  test("no price and no enrichment is an honest Unknown") {
    assert(DealScorer.scoreDeal(obs(None), stats(Some(300L), Some(200L), pricedN = 8)) == DealVerdict.Unknown)
  }

  test("no history and no enrichment is Unknown, not a guess") {
    assert(DealScorer.scoreDeal(obs(Some(250L)), stats(None, None, pricedN = 0)) == DealVerdict.Unknown)
  }

  test("thresholds are configurable") {
    val shallow = DealScorer.scoreDeal(
      obs(Some(285L)),
      stats(Some(300L), Some(200L), pricedN = 8),
      thresholds = DealThresholds(belowUsualPct = 20),
    )
    assert(shallow == DealVerdict.Notable) // 5% below: under the 20% bar, but still under the median

    val deep = DealScorer.scoreDeal(
      obs(Some(285L)),
      stats(Some(300L), Some(200L), pricedN = 8),
      thresholds = DealThresholds(belowUsualPct = 5),
    )
    assert(deep == DealVerdict.BelowUsual(5))
  }

  test("percentBelow floors at zero and tolerates a zero baseline") {
    assert(DealScorer.percentBelow(Money.cents(400), Money.cents(300)) == 0)
    assert(DealScorer.percentBelow(Money.cents(100), Money.cents(0)) == 0)
    assert(DealScorer.percentBelow(Money.cents(150), Money.cents(300)) == 50)
  }

  test("verdict phrases read as an operator would want them") {
    assert(DealVerdict.BelowUsual(15).phrase == "15% below usual")
    assert(DealVerdict.Unknown.phrase == "no price history yet")
    assert(DealVerdict.BestEver(8).isNotableOrBetter)
    assert(!DealVerdict.Unknown.isNotableOrBetter)
  }
}
