package demeter.normalization

import demeter.foundations.{Locale, Money}
import org.scalatest.funsuite.AnyFunSuite

/** Spec 02.2 — multi-buy promotions to a per-unit price. Tags: @pure. */
final class MultiBuyParserSpec extends AnyFunSuite {

  private def parse(text: String, base: Option[Money] = None, locale: Locale = Locale.EnCa) =
    MultiBuyParser.parseMultiBuy(text, base, locale)

  test("N-for-$X yields a half-even unit price") {
    val cases = Seq(
      ("2 for $5", Locale.EnCa, 2, 500L, 250L),
      ("3/$5.00", Locale.EnCa, 3, 500L, 167L), // 500/3 pins half-even vs floor
      ("2 pour 5 $", Locale.FrCa, 2, 500L, 250L),
      ("5 for $10", Locale.EnCa, 5, 1000L, 200L),
      ("3 for $4", Locale.EnCa, 3, 400L, 133L),
    )
    for ((text, locale, qty, bundle, unit) <- cases) {
      val Some(mb) = parse(text, locale = locale)
      assert(mb.quantity == qty, s"text: $text")
      assert(mb.bundlePrice.map(_.cents) == Some(bundle), s"text: $text")
      assert(mb.unitPrice.map(_.cents) == Some(unit), s"text: $text")
      assert(mb.freeQuantity == 0, s"text: $text")
    }
  }

  test("buy-N-get-M-free with a known base price yields an effective unit price") {
    val Some(mb) = parse("buy 2 get 1 free", base = Some(Money.cents(300)))
    assert(mb.quantity == 2)
    assert(mb.freeQuantity == 1)
    assert(mb.unitPrice.map(_.cents) == Some(200L)) // 2 paid * 3.00 over 3 items
    assert(mb.bundlePrice.isEmpty)
  }

  test("buy-N-get-M-free without a base price yields structure but no unit price") {
    val Some(mb) = parse("buy 2 get 1 free")
    assert(mb.freeQuantity == 1)
    assert(mb.unitPrice.isEmpty)
  }

  test("buy one get one free — the most common promo there is — halves the unit price") {
    val Some(mb) = parse("buy 1 get 1 free", base = Some(Money.cents(300)))
    assert(mb.quantity == 1)
    assert(mb.freeQuantity == 1)
    assert(mb.unitPrice.map(_.cents) == Some(150L)) // one paid at 3.00 over 2 items
  }

  test("a degenerate buy-N-get-M is refused rather than producing nonsense") {
    // a zero on either side would yield a bogus per-unit price (or divide by zero)
    assert(parse("buy 0 get 1 free", base = Some(Money.cents(300))).isEmpty)
    assert(parse("buy 2 get 0 free", base = Some(Money.cents(300))).isEmpty)
  }

  test("a single-item offer is not a multibuy") {
    assert(parse("1 for $5").isEmpty)
  }

  test("text with no multibuy structure returns nothing") {
    for (text <- Seq("$4.99", "50% off", "", "save $2", "25 points"))
      assert(parse(text).isEmpty, s"text: $text")
  }

  test("a savings tail is ignored, not mis-parsed") {
    val Some(mb) = parse("2 for $5.00, save $1.98")
    assert(mb.quantity == 2)
    assert(mb.bundlePrice.map(_.cents) == Some(500L))
    assert(mb.unitPrice.map(_.cents) == Some(250L))
  }
}
