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

  // --- the pairing boundaries ---
  //
  // assignPair decides which side is which by comparing both sides' scores, and
  // it has three outcomes: French-first High, English-first High (the sides get
  // swapped), and an undecidable fallback that keeps the given order at Medium.
  // The cases below pin the edges between those three, where a tie or a single
  // failing half of a condition changes the answer. Mutation testing found every
  // one of them unguarded: the comparisons could be inverted, loosened to
  // include equality, or replaced outright, and nothing above noticed.

  test("an elision apostrophe alone is enough to call a side French") {
    // No diacritics and no lexicon word: "d'" is carrying the entire decision.
    // Without it the name scores 0-0 and degrades to both forms at Low.
    val result = BilingualSplitter.splitBilingual("Coeur d'artichaut")
    assert(result.text.fr.contains("Coeur d'artichaut"))
    assert(result.text.en.isEmpty, "a French-scoring name must not also be filed as English")
    assert(result.confidence == Confidence.High)
  }

  test("two French segments are not a language pair, so the order stands at Medium") {
    // The first side is confidently French, but the second is French too, so the
    // second half of the test (the other side looking English) fails. That is
    // undecidable, not a confident French-then-English pair.
    val result = BilingualSplitter.splitBilingual("Lait | Beurre")
    assert(result.text.fr.contains("Lait"), "the fallback keeps the given order")
    assert(result.text.en.contains("Beurre"))
    assert(result.confidence == Confidence.Medium, "two French sides cannot be a confident pair")
  }

  test("two English segments are not a language pair either") {
    // The mirror case: the first side is confidently English, but so is the
    // second, so nothing gets swapped and the fallback order is kept.
    val result = BilingualSplitter.splitBilingual("Milk | Butter")
    assert(result.text.fr.contains("Milk"), "the FR|EN fallback is positional, not a claim about language")
    assert(result.text.en.contains("Butter"))
    assert(result.confidence == Confidence.Medium)
  }

  test("a tied first side does not become French just because the second looks English") {
    // "Creme Ice" scores 1-1. A tie is not evidence, so even with a clearly
    // English partner this stays the Medium fallback rather than a High call.
    val result = BilingualSplitter.splitBilingual("Creme Ice | Milk")
    assert(result.text.fr.contains("Creme Ice"))
    assert(result.text.en.contains("Milk"))
    assert(result.confidence == Confidence.Medium, "a 1-1 tie must not be read as French")
  }

  test("a tied first side does not become English just because the second looks French") {
    // The same tie with a French partner. If the tie counted as English the
    // sides would swap and "Lait" would be filed as the French form -- which
    // happens to be true, and is exactly why the confidence must stay Medium:
    // the result would be right by luck, not by detection.
    val result = BilingualSplitter.splitBilingual("Creme Ice | Lait")
    assert(result.text.fr.contains("Creme Ice"), "the tied side must not be swapped away")
    assert(result.text.en.contains("Lait"))
    assert(result.confidence == Confidence.Medium)
  }

  test("an English first side pairs with a tied second side, and the sides swap") {
    // Here the first side IS decisive, and the second side merely has to not
    // out-score it in French. A 1-1 tie satisfies that, so this is a confident
    // English-then-French pair and the sides are swapped into place.
    val result = BilingualSplitter.splitBilingual("Milk | Creme Ice")
    assert(result.text.fr.contains("Creme Ice"), "the second side is assigned French")
    assert(result.text.en.contains("Milk"))
    assert(result.confidence == Confidence.High, "the deciding side is unambiguous, so this is not a fallback")
  }
}
