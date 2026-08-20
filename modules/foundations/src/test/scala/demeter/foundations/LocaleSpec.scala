package demeter.foundations

import org.scalatest.funsuite.AnyFunSuite

/** Spec 00.3 — Locale & BilingualText. Tags: @pure. */
final class LocaleSpec extends AnyFunSuite {

  test("locale renders to the exact endpoint query value") {
    assert(Locale.FrCa.queryValue == "fr-ca")
    assert(Locale.EnCa.queryValue == "en-ca")
  }

  test("primary prefers the requested language") {
    assert(BilingualText(Some("lait"), Some("milk")).primary(Locale.FrCa).contains("lait"))
  }

  test("primary falls back to the other language when the preferred is absent") {
    assert(BilingualText(None, Some("milk")).primary(Locale.FrCa).contains("milk"))
  }

  test("primary is None when both are absent") {
    assert(BilingualText.empty.primary(Locale.EnCa).isEmpty)
  }

  test("forms exposes every present language for matching, deduplicated") {
    assert(BilingualText(Some("lait"), Some("milk")).forms == List("lait", "milk"))
  }

  test("forms deduplicates when both languages carry identical text") {
    assert(BilingualText(Some("Coca-Cola"), Some("Coca-Cola")).forms == List("Coca-Cola"))
  }

  test("anyForm prefers English then French") {
    assert(BilingualText(Some("lait"), Some("milk")).anyForm.contains("milk"))
    assert(BilingualText(Some("lait"), None).anyForm.contains("lait"))
  }
}
