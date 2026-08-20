package demeter.normalization

import demeter.foundations.{BilingualText, Locale, MerchantId}
import org.scalacheck.Gen
import org.scalatest.funsuite.AnyFunSuite
import org.scalatestplus.scalacheck.ScalaCheckPropertyChecks

/** Spec 02.7 — stable product key derivation. Tags: @pure (+ @property). */
final class ProductKeysSpec extends AnyFunSuite with ScalaCheckPropertyChecks {

  private def key(merchant: Int, name: String) =
    ProductKeys.productKey(
      MerchantId(merchant),
      BilingualText.enOnly(name),
      UnitPriceCalculator.parseSize(name, Locale.EnCa),
    )

  test("the same product across two weeks yields the same key") {
    assert(key(123, "Natrel Milk 4 L") == key(123, "NATREL MILK 4L"))
  }

  test("accents and case do not change the key") {
    val a = ProductKeys.productKey(MerchantId(123), BilingualText.frOnly("LAIT NATREL 4 L"), None)
    val b = ProductKeys.productKey(MerchantId(123), BilingualText.frOnly("lait natrel 4 l"), None)
    assert(a == b)
  }

  test("different sizes yield different keys") {
    assert(key(123, "Natrel Milk 2 L") != key(123, "Natrel Milk 4 L"))
  }

  test("the same product at different merchants yields different keys") {
    assert(key(123, "Natrel Milk 4 L") != key(456, "Natrel Milk 4 L"))
  }

  test("a missing size still yields a deterministic key") {
    assert(key(123, "Assorted Hand Tools") == key(123, "Assorted Hand Tools"))
  }

  test("field boundaries are real: merchant and name cannot bleed into each other") {
    // without a delimiter between the fields, merchant 1 + name "23" and
    // merchant 12 + name "3" would hash the same payload and collide
    assert(key(1, "23") != key(12, "3"))
  }

  test("the key carries the normalization version stamp, at a stable width") {
    val k = key(123, "Natrel Milk 4 L").value
    assert(k.startsWith("v1:"))
    // the width is a storage contract (03.1 keys the product table on it)
    assert(k.length == "v1:".length + 32, s"unexpected key width: $k")
  }

  test("the derived key is pinned, so a format change can never orphan history silently") {
    // 02.7: the hash and normalization version are stamped in precisely so that
    // improving normalization is a DELIBERATE migration. If this value changes,
    // every stored product_key in 03.1 stops matching — that must be a conscious
    // edit of this expectation, not a surprise.
    assert(key(123, "Natrel Milk 4 L").value == "v1:e528c05db58eafb09ab7394587cfa571")
  }

  test("a nameless product still keys deterministically, as an empty name") {
    val absent = ProductKeys.productKey(MerchantId(123), BilingualText.empty, None)
    val empty  = ProductKeys.productKey(MerchantId(123), BilingualText.enOnly(""), None)
    assert(absent == empty)
  }

  test("key derivation is a pure function (property)") {
    forAll(Gen.posNum[Int], Gen.alphaNumStr) { (merchant, name) =>
      assert(key(merchant, name) == key(merchant, name))
    }
  }
}
