package demeter.normalization

import demeter.foundations.{Locale, Money}
import org.scalacheck.Gen
import org.scalatest.funsuite.AnyFunSuite
import org.scalatestplus.scalacheck.ScalaCheckPropertyChecks

/** Spec 02.1 — parse a single money token from flyer free text. Tags: @pure. */
final class PriceTextParserSpec extends AnyFunSuite with ScalaCheckPropertyChecks {

  private def parse(text: String, locale: Locale = Locale.EnCa) =
    PriceTextParser.parsePriceToken(text, locale)

  test("common price tokens parse to exact cents") {
    val cases = Seq(
      ("$4.99", Locale.EnCa, 499L, None),
      ("4.99$", Locale.FrCa, 499L, None),
      ("4,99 $", Locale.FrCa, 499L, None),
      ("99¢", Locale.EnCa, 99L, None),
      ("99 cents", Locale.EnCa, 99L, None),
      (".99", Locale.EnCa, 99L, None),
      ("$1.50/lb", Locale.EnCa, 150L, Some(PriceUnit.PerLb)),
      ("3,29 $/kg", Locale.FrCa, 329L, Some(PriceUnit.PerKg)),
      ("$2.00 ea", Locale.EnCa, 200L, Some(PriceUnit.Each)),
      ("5,00 $ chacun", Locale.FrCa, 500L, Some(PriceUnit.Each)),
    )
    for ((text, locale, cents, unit) <- cases) {
      val result = parse(text, locale)
      assert(result.map(_.amount.cents) == Some(cents), s"text: $text")
      assert(result.flatMap(_.unit) == unit, s"text: $text")
    }
  }

  test("non-price text yields nothing") {
    for (text <- Seq("see store", "BOGO", "", "prix", "free"))
      assert(parse(text).isEmpty, s"text: $text")
  }

  test("the comma-decimal concern is fully resolved before Money sees it") {
    assert(parse("1 234,05 $", Locale.FrCa).map(_.amount.cents) == Some(123405L))
  }

  test("english thousands grouping resolves too") {
    assert(parse("$1,234.05").map(_.amount.cents) == Some(123405L))
  }

  test("grouping and decimal marks are resolved by position, not by guessing") {
    // English: dot is the decimal mark, comma groups
    assert(parse("$1,234.05").map(_.amount.cents) == Some(123405L))
    // European/Quebec: comma is the decimal mark, dot groups — the mirror image,
    // and the only case that proves the ordering logic actually looks at position
    assert(parse("1.234,05 $", Locale.FrCa).map(_.amount.cents) == Some(123405L))
    // grouping with no decimal part at all
    assert(parse("$1,234").map(_.amount.cents) == Some(123400L))
    // repeated grouping: the LAST separator is the one that decides, not the first
    assert(parse("$1,234,567").map(_.amount.cents) == Some(123456700L))
  }

  test("an unresolvable separator pattern is refused rather than guessed at") {
    assert(parse("$1,23,45").isEmpty) // repeated commas: neither grouping nor decimal
    assert(parse("$1,2345").isEmpty)  // four digits after a comma is neither

    // Interleaved separators, where the FIRST and the LAST mark of a kind
    // disagree about which one is the decimal. Only the last may decide. Read
    // the first instead and both of these resolve to a perfectly plausible
    // $12.34 -- a wrong price that looks entirely reasonable, which is the
    // worst failure this parser has, since nothing downstream would flag it.
    assert(parse("$1.2,3.4").isEmpty)
    assert(parse("$1,2.3,4").isEmpty)
  }

  test("a bare number with no currency signal is a size, not a price") {
    // "Milk 4 L" must not yield 4 cents — a price needs a symbol or a decimal part
    assert(parse("4").isEmpty)
    assert(parse("1234").isEmpty)
    assert(parse("$4").map(_.amount.cents) == Some(400L)) // ...but a symbol makes it one
  }

  test("any amount formatted with a currency symbol round-trips (property)") {
    forAll(Gen.chooseNum(1L, 9999999L)) { cents =>
      val formatted = "$" + Money.cents(cents).format
      assert(parse(formatted).map(_.amount.cents) == Some(cents))
    }
  }
}
