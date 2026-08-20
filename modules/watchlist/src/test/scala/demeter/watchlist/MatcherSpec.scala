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

  test("short tokens do not fuzzy-match, because Jaro-Winkler cannot tell them apart") {
    // butter~butt and yogourt~yoghurt BOTH score 0.933: no threshold separates
    // the wanted variant from the unwanted word, but length does
    assert(Matcher.matchItem(watch(List("butter")), obs(List("boneless pork shoulder butt"))).isEmpty)
    assert(Matcher.matchItem(watch(List("butter")), obs(List("save money live better"))).isEmpty)
    // the case the fallback exists for still works
    assert(Matcher.matchItem(watch(List("yogourt")), obs(List("greek yoghurt"))).isDefined)
  }

  test("the length rule gates only fuzzy matching, never exact containment") {
    // "cafe" is 4 characters — well under the floor — and must still match exactly
    assert(Matcher.matchItem(watch(List("cafe")), obs(List("cafe instant"))).isDefined)
    assert(Matcher.matchItem(watch(List("cafe")), obs(List("café instantané"))).isDefined)
    assert(Matcher.matchItem(watch(List("milk")), obs(List("natrel milk"))).isDefined)
    assert(Matcher.matchItem(watch(List("butter")), obs(List("lactantia butter"))).isDefined)
  }

  test("the floor applies to both sides, and is a knob") {
    val cfg = MatcherConfig()
    assert(!Matcher.canFuzzyMatch("butter", "butt", cfg), "short candidate")
    assert(!Matcher.canFuzzyMatch("butt", "butter", cfg), "short term")
    assert(Matcher.canFuzzyMatch("yogourt", "yoghurt", cfg))

    val off = MatcherConfig(minFuzzyLength = 0)
    assert(Matcher.canFuzzyMatch("butter", "butt", off), "0 disables the rule")
    assert(Matcher.matchItem(watch(List("butter")), obs(List("pork shoulder butt")), off).isDefined)
  }

  test("a near-miss that shares only a common word does not match") {
    assert(Matcher.matchItem(watch(List("chicken breast")), obs(List("chicken broth"))).isEmpty)
  }

  test("an exact containment scores higher than a fuzzy match") {
    val exact = Matcher.matchItem(watch(List("yoghurt")), obs(List("greek yoghurt"))).get
    val fuzzy = Matcher.matchItem(watch(List("yogourt")), obs(List("greek yoghurt"))).get
    assert(exact.textScore > fuzzy.textScore)
    // no longer 1.0: the term accounts for one of two tokens. A perfect score is
    // reserved for a term that accounts for the whole name.
    assert(Matcher.matchItem(watch(List("yoghurt")), obs(List("yoghurt"))).get.textScore == 1.0)
  }

  test("a term buried in a long name ranks below the same term in a short one") {
    // the real case: a watch for coffee matched a $1,799 patio set as strongly
    // as it matched actual coffee, and outranked it on price
    val patio = Matcher
      .matchItem(
        watch(List("coffee")),
        obs(List("montego 6 piece canopy outdoor patio conversation set with canopy sofa 2 armless chairs ottoman glass top coffee end table")),
      )
      .get
    val actualCoffee = Matcher.matchItem(watch(List("coffee")), obs(List("nabob ground coffee 300 g"))).get

    assert(actualCoffee.textScore > patio.textScore)
    assert(patio.textScore < 0.3, s"an incidental match must score low, got ${patio.textScore}")
  }

  test("normal grocery naming is not punished for having brand and descriptors") {
    // "milk" covers 1 of 6 tokens here, but this is exactly what a good grocery
    // match looks like; the raw ratio (0.17) would rank it near the patio set
    val milk = Matcher.matchItem(watch(List("milk")), obs(List("natrel fine filtered milk 4 l"))).get
    val patio = Matcher
      .matchItem(watch(List("coffee")), obs(List("montego 6 piece canopy outdoor patio conversation set with canopy sofa 2 armless chairs ottoman glass top coffee end table")))
      .get
    assert(milk.textScore > patio.textScore * 1.5, "a real grocery match must stay clearly ahead")
  }

  test("a term covering more of the name outranks one covering less") {
    val whole = Matcher.matchItem(watch(List("greek yoghurt")), obs(List("greek yoghurt"))).get
    val half  = Matcher.matchItem(watch(List("yoghurt")), obs(List("greek yoghurt"))).get
    assert(whole.textScore > half.textScore)
  }

  test("length dampening is a knob, and zero restores the original behaviour") {
    val off = MatcherConfig(lengthDampening = 0.0)
    assert(Matcher.matchItem(watch(List("coffee")), obs(List("a b c d e f g h coffee")), off).get.textScore == 1.0)
    assert(Matcher.shareOfName(1, 20, off) == 1.0)

    val raw = MatcherConfig(lengthDampening = 1.0)
    assert(Matcher.shareOfName(1, 4, raw) == 0.25, "dampening 1 is the plain token ratio")
    assert(Matcher.shareOfName(1, 4) == 0.5, "the default softens it")
  }

  test("a term longer than the name does not score above 1") {
    assert(Matcher.shareOfName(9, 2) == 1.0)
    val m = Matcher.matchItem(watch(List("milk")), obs(List("milk"))).get
    assert(m.textScore == 1.0)
  }

  test("length dampening changes ranking only, never whether something matched") {
    // every match assertion above still holds under the harshest setting
    val raw = MatcherConfig(lengthDampening = 1.0)
    assert(Matcher.matchItem(watch(List("milk")), obs(List("lait natrel", "natrel milk")), raw).isDefined)
    assert(Matcher.matchItem(watch(List("chicken breast")), obs(List("chicken broth")), raw).isEmpty)
    assert(Matcher.matchItem(watch(List("yogourt")), obs(List("greek yoghurt")), raw).isDefined)
  }

  test("terms are OR'd: any matching term is a match") {
    assert(Matcher.matchItem(watch(List("bread", "milk")), obs(List("natrel milk"))).isDefined)
  }

  test("accent-folded terms match accented item names") {
    assert(Matcher.matchItem(watch(List("cafe")), obs(List("café instantané"))).isDefined)
    assert(Matcher.matchItem(watch(List("café")), obs(List("cafe instant"))).isDefined)
  }
}
