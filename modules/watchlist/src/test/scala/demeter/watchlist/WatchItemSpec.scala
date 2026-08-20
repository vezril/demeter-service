package demeter.watchlist

import demeter.foundations.{MerchantId, Money}
import org.scalatest.funsuite.AnyFunSuite

/** Spec 04.1 — watch item validation. Tags: @pure. */
final class WatchItemSpec extends AnyFunSuite {

  private def watch(
      terms: List[String] = List("milk", "lait"),
      merchants: Set[MerchantId] = Set.empty,
      active: Boolean = true,
  ) = WatchItem.of(WatchId("w1"), "Milk 4L", terms, merchants = merchants, active = active)

  test("a watch item requires at least one term") {
    assert(watch(terms = Nil) == Left(WatchItem.InvalidWatch.NoTerms))
    assert(watch(terms = List("", "   ")) == Left(WatchItem.InvalidWatch.NoTerms))
  }

  test("a valid watch item carries its terms") {
    val Right(w) = watch()
    assert(w.terms.toList == List("milk", "lait"))
    assert(w.label == "Milk 4L")
  }

  test("requireSale defaults ON, so a watch does not alert on every match") {
    val Right(w) = WatchItem.of(WatchId("w1"), "Milk", List("milk"))
    assert(w.requireSale, "three watches with it off produced 384 alerts in one real run")
  }

  test("an empty merchant set means any merchant") {
    val Right(w) = watch()
    assert(w.inScope(MerchantId(123)))
  }

  test("a scoped watch item only includes its listed merchants") {
    val Right(w) = watch(merchants = Set(MerchantId(123), MerchantId(456)))
    assert(w.inScope(MerchantId(123)))
    assert(!w.inScope(MerchantId(789)))
  }

  test("an empty label is rejected") {
    assert(WatchItem.of(WatchId("w1"), "  ", List("milk")) == Left(WatchItem.InvalidWatch.EmptyLabel))
  }

  test("an out-of-range minimum discount is rejected") {
    assert(WatchItem.of(WatchId("w1"), "Milk", List("milk"), minDiscountPct = Some(0)).isLeft)
    assert(WatchItem.of(WatchId("w1"), "Milk", List("milk"), minDiscountPct = Some(101)).isLeft)
    assert(WatchItem.of(WatchId("w1"), "Milk", List("milk"), minDiscountPct = Some(20)).isRight)
  }

  test("alerting conditions are held here, not evaluated") {
    val Right(w) = WatchItem.of(
      WatchId("w1"),
      "Milk",
      List("milk"),
      maxPrice = Some(Money.cents(300)),
      requireSale = true,
      minDiscountPct = Some(20),
    )
    assert(w.maxPrice.contains(Money.cents(300)))
    assert(w.requireSale)
    assert(w.minDiscountPct.contains(20))
  }
}
