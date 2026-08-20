package demeter.watchlist

import demeter.foundations.PriceObservation
import demeter.normalization.TextNormalizer

/** Spec 04.3 — does an observation match a watch item?
  *
  * Layered: merchant scope short-circuit -> token containment over every
  * bilingual form -> fuzzy fallback (Jaro-Winkler per token) for spelling
  * variance. Terms within a watch are OR'd. Multi-token terms must cover every
  * token (exactly or fuzzily), which is what keeps "chicken breast" off
  * "chicken broth" while "yogourt" still finds "greek yoghurt".
  */
final case class MatcherConfig(
    fuzzyThreshold: Double = 0.90,
    stopwords: Set[String] = TextNormalizer.DefaultStopwords,
)

final case class TextMatch(term: String, textScore: Double)

object Matcher {

  def matchItem(watch: WatchItem, obs: PriceObservation, config: MatcherConfig = MatcherConfig()): Option[TextMatch] =
    if (!watch.active || !watch.inScope(obs.merchantId)) None
    else {
      val forms = {
        val fs = obs.name.forms
        if (fs.nonEmpty) fs else List(obs.rawName)
      }
      val formTokens = forms.map(f => TextNormalizer.normalize(f, config.stopwords).tokens).filter(_.nonEmpty)
      watch.terms.toList.flatMap(term => matchTerm(term, formTokens, config)).sortBy(-_.textScore).headOption
    }

  private def matchTerm(term: String, formTokens: List[List[String]], config: MatcherConfig): Option[TextMatch] = {
    val termTokens = TextNormalizer.normalize(term, config.stopwords).tokens
    if (termTokens.isEmpty) None
    else
      formTokens.flatMap { tokens =>
        // every term token must be covered by some form token: exact, or JW >= threshold
        val coverage = termTokens.map { tt =>
          if (tokens.contains(tt)) Some(1.0)
          else tokens.map(jaroWinkler(tt, _)).maxOption.filter(_ >= config.fuzzyThreshold)
        }
        if (coverage.forall(_.isDefined)) Some(coverage.flatten.sum / coverage.size) else None
      }.maxOption.map(TextMatch(term, _))
  }

  /** Standard Jaro-Winkler similarity (prefix bonus capped at 4). */
  def jaroWinkler(a: String, b: String): Double =
    if (a == b) 1.0
    else if (a.isEmpty || b.isEmpty) 0.0
    else {
      val window  = math.max(0, math.max(a.length, b.length) / 2 - 1)
      val bTaken  = Array.fill(b.length)(false)
      val aMatch  = Array.fill(a.length)(-1)
      var matches = 0
      for (i <- a.indices) {
        val lo = math.max(0, i - window)
        val hi = math.min(b.length - 1, i + window)
        var j  = lo
        var found = false
        while (j <= hi && !found) {
          if (!bTaken(j) && a(i) == b(j)) { bTaken(j) = true; aMatch(i) = j; matches += 1; found = true }
          j += 1
        }
      }
      if (matches == 0) 0.0
      else {
        val bOrder         = aMatch.filter(_ >= 0)
        val transpositions = bOrder.zip(bOrder.sorted).count { case (x, y) => x != y } / 2.0
        val m              = matches.toDouble
        val jaro           = (m / a.length + m / b.length + (m - transpositions) / m) / 3.0
        val prefix         = a.zip(b).takeWhile { case (x, y) => x == y }.size.min(4)
        jaro + prefix * 0.1 * (1.0 - jaro)
      }
    }
}
