package demeter.normalization

import org.scalacheck.Gen
import org.scalatest.funsuite.AnyFunSuite
import org.scalatestplus.scalacheck.ScalaCheckPropertyChecks

/** Spec 04.2 — matching text normalization (lives here for 02.7's reuse). Tags: @pure. */
final class TextNormalizerSpec extends AnyFunSuite with ScalaCheckPropertyChecks {

  test("accents and case are folded") {
    val cases = Seq(
      "Café"         -> "cafe",
      "CRÈME GLACÉE" -> "creme glacee",
      "Coca-Cola"    -> "coca cola",
      "Lait 2%"      -> "lait 2",
      "Œufs"         -> "oeufs",
    )
    for ((input, expected) <- cases)
      assert(TextNormalizer.normalize(input).joined == expected, s"input: $input")
  }

  test("lower-case ligatures fold too, not just their capitals") {
    assert(TextNormalizer.normalize("cœur").joined == "coeur")
    assert(TextNormalizer.normalize("æther").joined == "aether")
    assert(TextNormalizer.normalize("Æther").joined == "aether")
  }

  test("stopwords are dropped") {
    val tokens = TextNormalizer.normalize("Beurre de pomme with cinnamon").tokens
    assert(tokens == List("beurre", "pomme", "cinnamon"))
  }

  test("whitespace runs collapse") {
    assert(TextNormalizer.normalize("  milk    4   L  ").joined == "milk 4 l")
  }

  test("digit-letter boundaries split so 4L and 4 L agree") {
    assert(TextNormalizer.normalize("Milk 4L").joined == TextNormalizer.normalize("Milk 4 L").joined)
    // both directions of the boundary insert a real separator, not an empty one
    assert(TextNormalizer.normalize("Milk4L").joined == "milk 4 l")
    assert(TextNormalizer.normalize("12x355mL").joined == "12 x 355 ml")
  }

  test("normalization is idempotent (property)") {
    forAll(Gen.asciiPrintableStr) { s =>
      val once = TextNormalizer.normalize(s)
      assert(TextNormalizer.normalize(once.joined) == once)
    }
  }
}
