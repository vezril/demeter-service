package demeter.normalization

import java.text.Normalizer

/** Spec 04.2 — the shared matching-text normalizer.
  *
  * Lives in the normalization module (not watchlist) because the product key
  * (02.7) reuses it and watchlist depends on this module, never the reverse.
  * Steps are ordered and pinned; normalization is idempotent.
  */
final case class NormalizedText(tokens: List[String]) {
  def joined: String = tokens.mkString(" ")
}

object TextNormalizer {

  /** Both languages; configurable per deployment (08.4). */
  val DefaultStopwords: Set[String] =
    Set("de", "du", "des", "le", "la", "les", "the", "a", "an", "and", "et", "avec", "with", "&")

  def normalize(text: String, stopwords: Set[String] = DefaultStopwords): NormalizedText = {
    // 1. NFKD + strip combining marks (é -> e); œ/æ ligatures folded explicitly
    //    (Unicode gives them no decomposition but French text needs oe/ae).
    val folded = Normalizer
      .normalize(text.replace("œ", "oe").replace("Œ", "OE").replace("æ", "ae").replace("Æ", "AE"), Normalizer.Form.NFKD)
      .replaceAll("\\p{M}+", "")
    // 2. lowercase
    val lower = folded.toLowerCase
    // 3. punctuation and symbols become spaces; digit<->letter boundaries split so
    //    "4L" and "4 L" normalize alike (02.7 stability, 04.3 containment)
    val spaced = lower
      .replaceAll("[^\\p{Alnum}]+", " ")
      .replaceAll("(?<=\\d)(?=\\p{Alpha})", " ")
      .replaceAll("(?<=\\p{Alpha})(?=\\d)", " ")
    // 4. collapse whitespace, trim; 5. drop stopwords
    val tokens = spaced.trim.split("\\s+").toList.filter(_.nonEmpty).filterNot(stopwords)
    NormalizedText(tokens)
  }
}
