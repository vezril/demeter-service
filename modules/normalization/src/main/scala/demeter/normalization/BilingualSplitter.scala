package demeter.normalization

import demeter.foundations.{BilingualText, Confidence}

/** Spec 02.5 — split Flipp's jammed-together bilingual names into a
  * BilingualText. Deliberately cheap detection (diacritics + small lexicons),
  * honest about uncertainty: when unsure it degrades to "both forms" with Low
  * confidence, which is safe for matching. Never fabricates a translation and
  * never mutates the raw string.
  */
final case class SplitResult(text: BilingualText, confidence: Confidence)

object BilingualSplitter {

  // Spaced separators only — an unspaced dash is a hyphenated word, not a language split.
  private val Separators = List("\\|", "\\n", "\\s/\\s", "\\s-\\s")

  private val FrenchDiacritics = "àâäçéèêëîïôöùûüÿ".toSet

  private val FrenchWords: Set[String] = Set(
    "lait",
    "beurre",
    "fromage",
    "oeuf",
    "oeufs",
    "pain",
    "jus",
    "poulet",
    "boeuf",
    "porc",
    "jambon",
    "yogourt",
    "legumes",
    "fruits",
    "pomme",
    "pommes",
    "arachide",
    "arachides",
    "croquant",
    "creme",
    "glacee",
    "sucre",
    "farine",
    "gratuit",
    "rabais",
    "prix",
    "moitie",
    "chacun",
    "paquet",
    "boite",
    "surgele",
    "frais",
    "fume",
    "filtre",
    "finement",
    "saveur",
    "biologique",
    "poisson",
    "saumon",
    "riz",
    "cafe",
    "the",
    "eau",
    "de",
    "du",
    "des",
    "au",
    "aux",
    "avec",
    "et",
    "pour",
    "sans",
    "sur",
  )

  private val EnglishWords: Set[String] = Set(
    "milk",
    "butter",
    "cheese",
    "egg",
    "eggs",
    "bread",
    "juice",
    "chicken",
    "beef",
    "pork",
    "ham",
    "yogurt",
    "vegetables",
    "fruit",
    "apple",
    "apples",
    "peanut",
    "crunchy",
    "cream",
    "ice",
    "sugar",
    "flour",
    "free",
    "price",
    "half",
    "each",
    "pack",
    "box",
    "frozen",
    "fresh",
    "smoked",
    "filtered",
    "finely",
    "fine",
    "flavour",
    "flavor",
    "organic",
    "fish",
    "salmon",
    "rice",
    "coffee",
    "tea",
    "water",
    "the",
    "with",
    "and",
    "for",
    "of",
    "shelf",
    "rack",
    "resin",
    "tool",
    "tools",
    "set",
  )

  def splitBilingual(raw: String): SplitResult = {
    val segments = Separators
      .foldLeft(List(raw))((segs, sep) => segs.flatMap(_.split(sep).toList))
      .map(_.trim)
      .filter(_.nonEmpty)

    segments match {
      case Nil           => SplitResult(BilingualText.empty, Confidence.Low)
      case single :: Nil => detectSingle(single)
      case first :: rest =>
        // three-plus segments (rare): take the outer two as the language pair, Low confidence
        val second     = rest.last
        val degraded   = rest.size > 1
        val (bt, conf) = assignPair(first, second)
        SplitResult(bt, if (degraded) Confidence.Low else conf)
    }
  }

  /** (frenchScore, englishScore) — diacritics weigh double, lexicon hits single. */
  private def scores(s: String): (Int, Int) = {
    val lower  = s.toLowerCase
    val tokens = TextNormalizer.normalize(s, stopwords = Set.empty).tokens
    val fr =
      lower.count(FrenchDiacritics) * 2 + tokens.count(FrenchWords) + (if (lower.contains("d'") || lower.contains("l'"))
                                                                         1
                                                                       else 0)
    val en = tokens.count(EnglishWords)
    (fr, en)
  }

  private def detectSingle(s: String): SplitResult = {
    val (fr, en) = scores(s)
    if (fr > en) SplitResult(BilingualText(Some(s), None), Confidence.High)
    else if (en > fr) SplitResult(BilingualText(None, Some(s)), Confidence.High)
    else SplitResult(BilingualText(Some(s), Some(s)), Confidence.Low) // ambiguous: both forms, safe for matching
  }

  private def assignPair(a: String, b: String): (BilingualText, Confidence) = {
    val (frA, enA) = scores(a)
    val (frB, enB) = scores(b)
    if (frA > enA && enB >= frB) (BilingualText(Some(a), Some(b)), Confidence.High)
    else if (enA > frA && frB >= enB) (BilingualText(Some(b), Some(a)), Confidence.High)
    // undetectable: fall back to the observed Quebec convention (FR | EN), medium confidence
    else (BilingualText(Some(a), Some(b)), Confidence.Medium)
  }
}
