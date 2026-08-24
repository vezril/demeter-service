package demeter.alerting

import demeter.foundations.Money
import demeter.pricehistory.DealVerdict
import org.scalatest.funsuite.AnyFunSuite
import AlertingFixtures._

/** Spec 05.1 — decide whether a match is an alertable deal. Tags: @pure. */
final class DealDecisionSpec extends AnyFunSuite {

  test("a price at or below max price with a good verdict alerts") {
    val w = watch(maxPrice = Some(300L), requireSale = true)
    val d = DealDecision.decide(matched(w, obs(Some(250L))), DealVerdict.BestEver(8))
    assert(d.isInstanceOf[AlertDecision.Alert])
  }

  test("a price above max price is suppressed") {
    val w = watch(maxPrice = Some(300L))
    val d = DealDecision.decide(matched(w, obs(Some(349L))), DealVerdict.BelowUsual(15))
    assert(d == AlertDecision.Suppress(SuppressReason.AboveMaxPrice))
    assert(SuppressReason.AboveMaxPrice.message == "above max price")
  }

  test("a price exactly at max price alerts (at or below)") {
    val w = watch(maxPrice = Some(300L))
    assert(DealDecision.decide(matched(w, obs(Some(300L))), DealVerdict.Notable).isInstanceOf[AlertDecision.Alert])
  }

  test("requireSale suppresses a normal-priced match") {
    val w = watch(requireSale = true)
    val d = DealDecision.decide(matched(w, obs(Some(250L))), DealVerdict.AtOrAboveUsual)
    assert(d == AlertDecision.Suppress(SuppressReason.NotASale))
  }

  test("a no-price promo passes only when no max price is set") {
    val promo = obs(cents = None, saleText = Some("50% off"))
    assert(DealDecision.decide(matched(watch(), promo), DealVerdict.Unknown).isInstanceOf[AlertDecision.Alert])

    val gated = DealDecision.decide(matched(watch(maxPrice = Some(300L)), promo), DealVerdict.Unknown)
    assert(gated == AlertDecision.Suppress(SuppressReason.PriceUnknown))
  }

  test("minDiscountPct gates on discount depth") {
    // 10% off its own original: below a 20% floor
    val w = watch(minDiscountPct = Some(20))
    val d = DealDecision.decide(matched(w, obs(Some(270L), original = Some(300L))), DealVerdict.BelowUsual(10))
    assert(d == AlertDecision.Suppress(SuppressReason.DiscountTooShallow))

    val deep = DealDecision.decide(matched(w, obs(Some(210L), original = Some(300L))), DealVerdict.BelowUsual(30))
    assert(deep.isInstanceOf[AlertDecision.Alert])
  }

  test("discount depth uses the enrichment regular over the flyer's claim") {
    val w = watch(minDiscountPct = Some(20))
    // flyer claims regular 39.99 -> 25% off; enrichment says the real regular is 29.99 -> 0% off
    val o = obs(Some(2999L), original = Some(3999L))
    assert(DealDecision.decide(matched(w, o), DealVerdict.BelowUsual(25)).isInstanceOf[AlertDecision.Alert])

    val corrected =
      DealDecision.decide(matched(w, o), DealVerdict.AtOrAboveUsual, enrichedRegular = Some(Money.cents(2999)))
    assert(corrected == AlertDecision.Suppress(SuppressReason.DiscountTooShallow))
  }

  test("an unprovable discount does not alert when a floor is set") {
    val w = watch(minDiscountPct = Some(20))
    val d = DealDecision.decide(matched(w, obs(Some(250L), original = None)), DealVerdict.BelowUsual(30))
    assert(d == AlertDecision.Suppress(SuppressReason.DiscountTooShallow))
  }

  test("an inactive watch never alerts") {
    val d = DealDecision.decide(matched(watch(active = false), obs()), DealVerdict.BestEver(8))
    assert(d == AlertDecision.Suppress(SuppressReason.WatchInactive))
  }

  test("gate order is pinned: max price is evaluated before requireSale") {
    val w = watch(maxPrice = Some(200L), requireSale = true)
    val d = DealDecision.decide(matched(w, obs(Some(349L))), DealVerdict.AtOrAboveUsual)
    assert(d == AlertDecision.Suppress(SuppressReason.AboveMaxPrice))
  }

  test("an alerting deal carries the verdict and score through") {
    val m = matched(watch(), obs(Some(250L)))
    DealDecision.decide(m, DealVerdict.BestEver(8)) match {
      case AlertDecision.Alert(deal) =>
        assert(deal.verdict == DealVerdict.BestEver(8))
        assert(deal.score == m.score.combined)
      case other => fail(s"expected an alert, got $other")
    }
  }
}
