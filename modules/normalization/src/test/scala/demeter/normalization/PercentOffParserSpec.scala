package demeter.normalization

import demeter.foundations.{Locale, Money}
import org.scalatest.funsuite.AnyFunSuite

/** Spec 02.3 — percentage-off promotions. Tags: @pure. */
final class PercentOffParserSpec extends AnyFunSuite {

  private def parse(text: String, base: Option[Money] = None, locale: Locale = Locale.EnCa) =
    PercentOffParser.parsePercentOff(text, base, locale)

  test("percent expressions parse to a rate") {
    val cases = Seq(
      ("50% off", Locale.EnCa, 50, false),
      ("Save 25%", Locale.EnCa, 25, false),
      ("40% de rabais", Locale.FrCa, 40, false),
      ("rabais de 30%", Locale.FrCa, 30, false),
      ("up to 40% off", Locale.EnCa, 40, true),
      ("jusqu'à 40%", Locale.FrCa, 40, true),
      ("1/2 price", Locale.EnCa, 50, false),
      ("moitié prix", Locale.FrCa, 50, false),
    )
    for ((text, locale, rate, bound) <- cases) {
      val Some(po) = parse(text, locale = locale)
      assert(po.rate == rate, s"text: $text")
      assert(po.isUpperBound == bound, s"text: $text")
    }
  }

  test("with a base price, the sale price is computed") {
    val Some(po) = parse("25% off", base = Some(Money.cents(2000)))
    assert(po.rate == 25)
    assert(po.salePrice.map(_.cents) == Some(1500L))
  }

  test("without a base price, the rate is recorded but no sale price") {
    val Some(po) = parse("50% off")
    assert(po.rate == 50)
    assert(po.salePrice.isEmpty)
  }

  test("the full 1..100 rate range is accepted at both ends") {
    assert(parse("1% off").map(_.rate) == Some(1))
    assert(parse("100% off").map(_.rate) == Some(100))
  }

  test("a nonsensical rate is refused") {
    assert(parse("0% off").isEmpty)
    assert(parse("150% off").isEmpty)
  }

  test("non-percent text returns nothing") {
    for (text <- Seq("2 for $5", "$4.99", "", "25 points"))
      assert(parse(text).isEmpty, s"text: $text")
  }

  test("a bare free claim is 100% off, but a BOGO is not") {
    assert(parse("free").map(_.rate) == Some(100))
    assert(parse("gratuit").map(_.rate) == Some(100))
    assert(parse("buy 2 get 1 free").isEmpty)
  }
}
