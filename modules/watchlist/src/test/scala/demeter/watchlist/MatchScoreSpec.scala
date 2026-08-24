package demeter.watchlist

import java.time.Instant

import demeter.foundations._
import org.scalacheck.Gen
import org.scalatest.funsuite.AnyFunSuite
import org.scalatestplus.scalacheck.ScalaCheckPropertyChecks

/** Spec 04.4 — score and rank matches. Tags: @pure (+ @property). */
final class MatchScoreSpec extends AnyFunSuite with ScalaCheckPropertyChecks {

  private val at = Instant.parse("2026-07-23T00:00:00Z")

  private def obs(cents: Option[Long], confidence: Confidence = Confidence.High): PriceObservation =
    PriceObservation(
      productKey = ProductKey("v1:k"),
      merchantId = MerchantId(123),
      flyerId = FlyerId(900L),
      observedAt = at,
      name = BilingualText.enOnly("milk"),
      rawName = "milk",
      effectivePrice = cents.map(Money.cents(_)),
      priceBasis = PriceBasis.ScalarPrice,
      originalPrice = None,
      size = None,
      unitPrice = None,
      saleText = None,
      validFrom = at,
      validTo = at.plusSeconds(604800),
      priceConfidence = confidence,
      matchConfidence = confidence,
    )

  private val watch = WatchItem.of(WatchId("w1"), "Milk", List("milk")).toOption.get

  test("a high-confidence observation outranks a low-confidence one at equal text score") {
    val high = MatchScore(1.0, Confidence.High, 1.0, ScoringWeights())
    val low  = MatchScore(1.0, Confidence.Low, 1.0, ScoringWeights())
    assert(high.combined > low.combined)
  }

  test("within a group the cheapest priced match ranks first, unknown price last") {
    val ranks = MatchScore.rankByPrice(List(Some(Money.cents(250)), Some(Money.cents(299)), None))
    assert(ranks.head == 1.0)     // 2.50 cheapest
    assert(ranks(1) < ranks.head) // 2.99 below it...
    assert(ranks(1) > ranks(2))   // ...but a known price still beats no price
    assert(ranks(2) == MatchScore.UnknownPriceRank)
  }

  test("a single known price ranks top") {
    assert(MatchScore.rankByPrice(List(Some(Money.cents(250)))) == List(1.0))
  }

  test("all-unknown prices rank equally at the bottom") {
    assert(MatchScore.rankByPrice(List(None, None)) == List(0.0, 0.0))
  }

  test("scoreGroup price-ranks within the matched group") {
    val matched = List(
      obs(Some(299L)) -> TextMatch("milk", 1.0),
      obs(Some(250L)) -> TextMatch("milk", 1.0),
    )
    val scored = MatchScore.scoreGroup(watch, matched)
    assert(scored.map(_.score.priceRank) == List(MatchScore.KnownPriceFloor, 1.0))
    assert(scored(1).score.combined > scored.head.score.combined)
  }

  test("combined score stays within 0..1 (property)") {
    val genConfidence = Gen.oneOf[Confidence](Confidence.High, Confidence.Medium, Confidence.Low)
    forAll(Gen.choose(0.0, 1.0), genConfidence, Gen.choose(0.0, 1.0)) { (text, conf, price) =>
      val combined = MatchScore(text, conf, price, ScoringWeights()).combined
      assert(combined >= 0.0 && combined <= 1.0)
    }
  }

  test("weights are configurable and normalized") {
    val textOnly = MatchScore(1.0, Confidence.Low, 0.0, ScoringWeights(text = 1.0, confidence = 0.0, price = 0.0))
    assert(textOnly.combined == 1.0)
    // unnormalized weights behave the same as their normalized form
    val a = MatchScore(0.5, Confidence.Medium, 0.5, ScoringWeights(4, 3, 3)).combined
    val b = MatchScore(0.5, Confidence.Medium, 0.5, ScoringWeights(0.4, 0.3, 0.3)).combined
    assert(math.abs(a - b) < 1e-9)
  }
}
