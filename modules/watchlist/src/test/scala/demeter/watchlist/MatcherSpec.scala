package demeter.watchlist

import java.time.Instant

import demeter.foundations._
import org.scalatest.funsuite.AnyFunSuite

/** Spec 04.3 — match observations to watch items. Tags: @pure. */
final class MatcherSpec extends AnyFunSuite {

  private val from = Instant.parse("2026-07-23T00:00:00Z")
  private val to   = Instant.parse("2026-07-30T00:00:00Z")

  private def obs(forms: List[String], merchant: Int = 123, cents: Option[Long] = Some(250L)): PriceObservation =
    PriceObservation(
      productKey = ProductKey("v1:k"),
      merchantId = MerchantId(merchant),
      flyerId = FlyerId(900L),
      observedAt = from,
      name = forms match {
        case fr :: en :: _ => BilingualText(Some(fr), Some(en))
        case one :: Nil    => BilingualText(None, Some(one))
        case Nil           => BilingualText.empty
      },
      rawName = forms.mkString(" | "),
      effectivePrice = cents.map(Money.cents(_)),
      priceBasis = PriceBasis.ScalarPrice,
      originalPrice = None,
      size = None,
      unitPrice = None,
      saleText = None,
      validFrom = from,
      validTo = to,
      priceConfidence = Confidence.High,
      matchConfidence = Confidence.High,
    )

  private def watch(terms: List[String], merchants: Set[MerchantId] = Set.empty, active: Boolean = true) =
    WatchItem.of(WatchId("w1"), "watch", terms, merchants = merchants, active = active).toOption.get

  test("an English term matches a French-named item via bilingual forms") {
    assert(Matcher.matchItem(watch(List("milk")), obs(List("lait natrel", "natrel milk"))).isDefined)
  }

  test("a French term matches the same item") {
    assert(Matcher.matchItem(watch(List("lait")), obs(List("lait natrel", "natrel milk"))).isDefined)
  }

  test("token containment ignores word order and extra words") {
    assert(Matcher.matchItem(watch(List("milk 4l")), obs(List("natrel fine filtered milk 4 l"))).isDefined)
  }

  test("an out-of-scope merchant short-circuits to no match") {
    val w = watch(List("milk"), merchants = Set(MerchantId(999)))
    assert(Matcher.matchItem(w, obs(List("natrel milk"), merchant = 123)).isEmpty)
  }

  test("an inactive watch item is skipped") {
    assert(Matcher.matchItem(watch(List("milk"), active = false), obs(List("natrel milk"))).isEmpty)
  }

  test("fuzzy fallback catches minor spelling variance but not near-misses") {
    assert(Matcher.matchItem(watch(List("yogourt")), obs(List("greek yoghurt"))).isDefined, "yogourt/yoghurt")
    assert(Matcher.matchItem(watch(List("milk")), obs(List("milkshake mix"))).isEmpty, "milk/milkshake")
    assert(Matcher.matchItem(watch(List("cafe")), obs(List("cafe instant"))).isDefined, "cafe")
  }

  test("a near-miss that shares only a common word does not match") {
    assert(Matcher.matchItem(watch(List("chicken breast")), obs(List("chicken broth"))).isEmpty)
  }

  test("an exact containment scores higher than a fuzzy match") {
    val exact = Matcher.matchItem(watch(List("yoghurt")), obs(List("greek yoghurt"))).get
    val fuzzy = Matcher.matchItem(watch(List("yogourt")), obs(List("greek yoghurt"))).get
    assert(exact.textScore > fuzzy.textScore)
    assert(exact.textScore == 1.0)
  }

  test("terms are OR'd: any matching term is a match") {
    assert(Matcher.matchItem(watch(List("bread", "milk")), obs(List("natrel milk"))).isDefined)
  }

  test("accent-folded terms match accented item names") {
    assert(Matcher.matchItem(watch(List("cafe")), obs(List("café instantané"))).isDefined)
    assert(Matcher.matchItem(watch(List("café")), obs(List("cafe instant"))).isDefined)
  }
}
