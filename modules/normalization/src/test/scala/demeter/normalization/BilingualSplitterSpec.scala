package demeter.normalization

import demeter.foundations.Confidence
import org.scalatest.funsuite.AnyFunSuite

/** Spec 02.5 — split bilingual flyer names. Tags: @pure. */
final class BilingualSplitterSpec extends AnyFunSuite {

  test("a pipe-separated bilingual name splits by detected language") {
    val result = BilingualSplitter.splitBilingual("LAIT FINEMENT FILTRÉ NATREL | NATREL FINE-FILTERED MILK")
    assert(result.text.fr.contains("LAIT FINEMENT FILTRÉ NATREL"))
    assert(result.text.en.contains("NATREL FINE-FILTERED MILK"))
    assert(result.confidence == Confidence.High)
  }

  test("various separators are recognized") {
    val cases = Seq(
      ("Lait / Milk", "Lait", "Milk"),
      ("Fromage - Cheese", "Fromage", "Cheese"),
      ("Beurre | Butter", "Beurre", "Butter"),
    )
    for ((raw, fr, en) <- cases) {
      val result = BilingualSplitter.splitBilingual(raw)
      assert(result.text.fr.contains(fr), s"raw: $raw")
      assert(result.text.en.contains(en), s"raw: $raw")
    }
  }

  test("language detection assigns sides, not positions") {
    // English first, French second — detection must still assign correctly
    val result = BilingualSplitter.splitBilingual("Butter | Beurre")
    assert(result.text.fr.contains("Beurre"))
    assert(result.text.en.contains("Butter"))
  }

  test("a confidently-French side pairs with an undetectable side without downgrading") {
    // "Cola 2L" is language-neutral; the French side is still unambiguous, so the
    // pairing is High confidence rather than a coin flip
    val result = BilingualSplitter.splitBilingual("Lait | Cola 2L")
    assert(result.text.fr.contains("Lait"))
    assert(result.text.en.contains("Cola 2L"))
    assert(result.confidence == Confidence.High)
  }

  test("a single-language name goes in the detected language only") {
    val result = BilingualSplitter.splitBilingual("MASTERCRAFT 5-Shelf Resin Rack")
    assert(result.text.en.contains("MASTERCRAFT 5-Shelf Resin Rack"))
    assert(result.text.fr.isEmpty)
    assert(result.confidence == Confidence.High)
  }

  test("a French-only name is detected as French") {
    val result = BilingualSplitter.splitBilingual("Beurre d'arachide croquant")
    assert(result.text.fr.contains("Beurre d'arachide croquant"))
    assert(result.text.en.isEmpty)
  }

  test("an ambiguous name is placed in both forms with low confidence") {
    val result = BilingualSplitter.splitBilingual("Cola 2L")
    assert(result.text.fr.contains("Cola 2L"))
    assert(result.text.en.contains("Cola 2L"))
    assert(result.confidence == Confidence.Low)
  }

  test("a three-segment pipe folds to the outer pair with low confidence") {
    val result = BilingualSplitter.splitBilingual("Lait 4L | Natrel | Milk 4L")
    assert(result.text.fr.contains("Lait 4L"))
    assert(result.text.en.contains("Milk 4L"))
    assert(result.confidence == Confidence.Low)
  }

  test("the raw name string is never altered") {
    val raw = "  Natrel 3.25%  |  LAIT 3.25%  "
    BilingualSplitter.splitBilingual(raw)
    assert(raw == "  Natrel 3.25%  |  LAIT 3.25%  ") // pure function; raw untouched elsewhere in the pipeline
  }
}
