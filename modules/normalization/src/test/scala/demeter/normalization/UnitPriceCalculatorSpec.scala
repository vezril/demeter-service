package demeter.normalization

import demeter.foundations.{Locale, Money, Size, StdUnit}
import org.scalatest.funsuite.AnyFunSuite

/** Spec 02.4 — size extraction and unit price computation. Tags: @pure. */
final class UnitPriceCalculatorSpec extends AnyFunSuite {

  private def size(name: String, locale: Locale = Locale.EnCa) =
    UnitPriceCalculator.parseSize(name, locale)

  test("sizes parse to a quantity and standard unit") {
    val cases = Seq(
      ("Natrel Milk 4 L", Locale.EnCa, BigDecimal(4), StdUnit.PerLitre, 1),
      ("Beurre 500 g", Locale.FrCa, BigDecimal(0.5), StdUnit.PerKg, 1),
      ("Flour 1.5kg", Locale.EnCa, BigDecimal(1.5), StdUnit.PerKg, 1),
      ("Pepsi 12 x 355 mL", Locale.EnCa, BigDecimal(4.26), StdUnit.PerLitre, 12),
      ("Yogourt paquet de 6", Locale.FrCa, BigDecimal(6), StdUnit.PerItem, 6),
      ("Eggs, dozen", Locale.EnCa, BigDecimal(12), StdUnit.PerItem, 12),
    )
    for ((name, locale, qty, unit, pack) <- cases) {
      val Some(s) = size(name, locale)
      assert(s.quantity == qty, s"name: $name")
      assert(s.unit == unit, s"name: $name")
      assert(s.packCount == pack, s"name: $name")
    }
  }

  test("spelled-out unit words parse like their symbols") {
    // the fr/en word lists are a separate branch from "g"/"kg"/"L"/"mL"
    val cases = Seq(
      ("Beurre 500 grammes", Locale.FrCa, BigDecimal(0.5), StdUnit.PerKg),
      ("Flour 500 grams", Locale.EnCa, BigDecimal(0.5), StdUnit.PerKg),
      ("Jus 1.5 litres", Locale.FrCa, BigDecimal(1.5), StdUnit.PerLitre),
      ("Soda 2 liters", Locale.EnCa, BigDecimal(2), StdUnit.PerLitre),
      ("Lait 1 litre", Locale.FrCa, BigDecimal(1), StdUnit.PerLitre),
      ("Sugar 1 gram", Locale.EnCa, BigDecimal(0.001), StdUnit.PerKg),
      ("Soda 1 liter", Locale.EnCa, BigDecimal(1), StdUnit.PerLitre),    // singular
      ("Beurre 1 gramme", Locale.FrCa, BigDecimal(0.001), StdUnit.PerKg), // singular
    )
    for ((name, locale, qty, unit) <- cases) {
      val Some(s) = size(name, locale)
      assert(s.quantity == qty, s"name: $name")
      assert(s.unit == unit, s"name: $name")
    }
  }

  test("centilitres convert to litres") {
    val Some(s) = size("Espresso 50 cl")
    assert(s.quantity == BigDecimal(0.5))
    assert(s.unit == StdUnit.PerLitre)
  }

  test("a non-positive size is refused rather than dividing by zero") {
    val err = intercept[IllegalArgumentException](
      UnitPriceCalculator.unitPrice(Money.cents(499), Size(BigDecimal(0), StdUnit.PerLitre, 1))
    )
    assert(err.getMessage.contains("size quantity must be positive"))
  }

  test("names with no size return nothing") {
    for (name <- Seq("Assorted Hand Tools", "MASTERCRAFT Toolbox", "Fresh Produce"))
      assert(size(name).isEmpty, s"name: $name")
  }

  test("price plus size yields a per-standard-unit price") {
    val cases = Seq(
      (499L, BigDecimal(4), StdUnit.PerLitre, 125L), // $4.99/4L ≈ $1.25/L
      (299L, BigDecimal(1), StdUnit.PerLitre, 299L),
      (500L, BigDecimal(0.5), StdUnit.PerKg, 1000L), // $5.00/500g = $10.00/kg
    )
    for ((cents, qty, unit, expected) <- cases) {
      val up = UnitPriceCalculator.unitPrice(Money.cents(cents), Size(qty, unit, 1))
      assert(up.price.cents == expected)
      assert(up.per == unit)
    }
  }

  test("a multipack is ONE size, not the pack plus its inner volume") {
    // "12 x 355 mL" contains a volume token ("355 mL") that also matches on its
    // own. It must be recognised as part of the multipack, not counted as a
    // second, competing size — otherwise the item reads as ambiguous and 02.6
    // needlessly drops its confidence.
    val Some(detail) = UnitPriceCalculator.parseSizeDetailed("Pepsi 12 x 355 mL", Locale.EnCa)
    assert(detail.size.quantity == BigDecimal(4.26))
    assert(!detail.ambiguous, "the inner volume must not count as a second size")
  }

  test("a size OUTSIDE a multipack is still its own candidate") {
    // only tokens genuinely inside the multipack span are absorbed; a separate
    // size earlier in the name is a real second candidate (first one wins)
    val Some(detail) = UnitPriceCalculator.parseSizeDetailed("Jus 1 L Pepsi 12 x 355 mL", Locale.EnCa)
    assert(detail.size.quantity == BigDecimal(1))
    assert(detail.size.unit == StdUnit.PerLitre)
    assert(detail.ambiguous, "two independent sizes is exactly what ambiguous means")
  }

  test("multipack volume is totalled before dividing") {
    val Some(s) = size("Pepsi 12 x 355 mL")
    assert(s.quantity == BigDecimal(4.26))
    val up = UnitPriceCalculator.unitPrice(Money.cents(799), s)
    assert(up.price.cents == 188L)
    assert(up.per == StdUnit.PerLitre)
  }

  test("multiple size-like tokens are flagged ambiguous, first wins") {
    val Some(detail) = UnitPriceCalculator.parseSizeDetailed("Juice 1 L bottle, 500 g net", Locale.EnCa)
    assert(detail.size.unit == StdUnit.PerLitre)
    assert(detail.ambiguous)
    val Some(single) = UnitPriceCalculator.parseSizeDetailed("Natrel Milk 4 L", Locale.EnCa)
    assert(!single.ambiguous)
  }
}
