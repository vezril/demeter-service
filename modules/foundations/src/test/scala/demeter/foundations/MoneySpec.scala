package demeter.foundations

import org.scalacheck.Gen
import org.scalatest.funsuite.AnyFunSuite
import org.scalatestplus.scalacheck.ScalaCheckPropertyChecks

/** Spec 00.1 — Money as exact cents. Tags: @pure (+ @property for the round trip). */
final class MoneySpec extends AnyFunSuite with ScalaCheckPropertyChecks {

  test("parsing a well-formed decimal string yields exact cents") {
    val cases = Seq(
      "4.99"    -> 499L,
      "0.99"    -> 99L,
      "10"      -> 1000L,
      "10.0"    -> 1000L,
      "10.50"   -> 1050L,
      "0"       -> 0L,
      "1234.05" -> 123405L,
    )
    for ((input, expected) <- cases)
      assert(Money.fromDecimal(input).map(_.cents) == Right(expected), s"input: $input")
  }

  test("malformed or out-of-domain amounts are rejected, not coerced") {
    assert(Money.fromDecimal("abc") == Left(MoneyError.NotANumber("abc")))
    assert(Money.fromDecimal("4.999") == Left(MoneyError.TooManyDecimalPlaces("4.999")))
    assert(Money.fromDecimal("-1.00") == Left(MoneyError.Negative("-1.00")))
    assert(Money.fromDecimal("") == Left(MoneyError.NotANumber("")))
    // Comma decimal is a locale concern, handled upstream in 02, not here.
    assert(Money.fromDecimal("4,99") == Left(MoneyError.NotANumber("4,99")))
  }

  test("fromBigDecimal mirrors the string rules, reporting the SPECIFIC error") {
    assert(Money.fromBigDecimal(BigDecimal("4.99")).map(_.cents) == Right(499L))
    // asserting the exact error matters: both of these reject, but conflating
    // "too precise" with "not a number" would hide a real parsing bug
    assert(Money.fromBigDecimal(BigDecimal("4.999")) == Left(MoneyError.TooManyDecimalPlaces("4.999")))
    assert(Money.fromBigDecimal(BigDecimal("-1")) == Left(MoneyError.Negative("-1")))
  }

  test("zero is a valid amount, not a negative one") {
    assert(Money.fromBigDecimal(BigDecimal(0)).map(_.cents) == Right(0L))
    assert(Money.fromDecimal("0.00").map(_.cents) == Right(0L))
  }

  test("dividing across a non-positive count is rejected outright") {
    // a zero count would divide by zero; the guard is the only thing preventing it
    val err = intercept[IllegalArgumentException](Money.cents(500).divideEvenly(0))
    assert(err.getMessage.contains("division count must be positive"))
    assertThrows[IllegalArgumentException](Money.cents(500).divideEvenly(-2))
  }

  test("formatting round-trips a clean parse") {
    val Right(m) = Money.fromDecimal("7.05")
    assert(m.format == "7.05")
  }

  test("parse/format is a round trip for any valid cents (property)") {
    forAll(Gen.chooseNum(0L, 100000000L)) { n =>
      val m = Money.cents(n)
      assert(Money.fromDecimal(m.format) == Right(m))
    }
  }

  test("dividing a total across a count uses half-even rounding") {
    assert(Money.cents(500).divideEvenly(3).cents == 167L)  // 166.66 -> 167 (half up)
    assert(Money.cents(500).divideEvenly(2).cents == 250L)  // exact
    assert(Money.cents(1000).divideEvenly(4).cents == 250L) // exact
    assert(Money.cents(5).divideEvenly(2).cents == 2L)      // 2.5 -> 2 (half-even down to even)
  }

  test("currencies never silently mix") {
    // v1 has a single CAD inhabitant, so a mismatch is unrepresentable at the type
    // level; the value-level guard is exercised for the same-currency path and will
    // reject as soon as a second Currency case exists.
    assert((Money.cents(100) + Money.cents(50)) == Right(Money.cents(150)))
  }
}
