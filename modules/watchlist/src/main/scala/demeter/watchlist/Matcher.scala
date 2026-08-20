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
    /** How hard to penalise a term that accounts for only a sliver of the item
      * name. 0 disables it entirely (the term's match quality alone, which is
      * what this did originally); 1 is the raw token ratio.
      *
      * Neither extreme is right. Without any penalty, a watch for "coffee"
      * matched a $1,799 patio set on "Glass Top Coffee & End Table" as strongly
      * as it matched a bag of coffee. With the raw ratio, "milk" against
      * "natrel fine filtered milk 4 l" scores 0.17 — but that is a genuinely
      * good match, because grocery names carry brand and descriptors around the
      * head noun. The square root sits between: it separates incidental matches
      * in long names from real ones without flattening normal grocery naming.
      */
    lengthDampening: Double = 0.5,
    /** Shortest token the fuzzy fallback will consider, on BOTH sides.
      *
      * Jaro-Winkler cannot distinguish a spelling variant from a different short
      * word, because one character is a large fraction of a short token. Real
      * scores from live data:
      *
      *   yogourt ~ yoghurt   0.933   the variant this fallback exists for
      *   butter  ~ butt      0.933   "BONELESS PORK SHOULDER BUTT"
      *   butter  ~ better    0.900   "Save money, live better"
      *
      * The wanted match and the unwanted one score IDENTICALLY, so no threshold
      * separates them — 14 of 83 butter alerts in one real run were this. Length
      * does separate them: at 7, `yogourt` still matches and `butter` stops
      * fuzzing entirely. The cost is that short plurals ("beurre"/"beurres") no
      * longer match fuzzily and need their own term, which is explicit and
      * cheap. 0 disables the rule.
      */
    minFuzzyLength: Int = 7,
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
        val quality = termTokens.map { tt =>
          if (tokens.contains(tt)) Some(1.0)
          else
            tokens
              .filter(canFuzzyMatch(tt, _, config))
              .map(jaroWinkler(tt, _))
              .maxOption
              .filter(_ >= config.fuzzyThreshold)
        }
        if (quality.forall(_.isDefined))
          Some((quality.flatten.sum / quality.size) * shareOfName(termTokens.size, tokens.size, config))
        else None
      }.maxOption.map(TextMatch(term, _))
  }

  /** Whether two tokens are long enough for Jaro-Winkler to mean anything.
    * Exact containment is unaffected — this gates only the fuzzy fallback.
    */
  def canFuzzyMatch(term: String, candidate: String, config: MatcherConfig = MatcherConfig()): Boolean =
    config.minFuzzyLength <= 0 ||
      (term.length >= config.minFuzzyLength && candidate.length >= config.minFuzzyLength)

  /** How much of the item name the term accounts for, dampened.
    *
    * Whether a term matched is unaffected — this only ranks matches against each
    * other, which is what decides the one alert you get when a dozen items match
    * the same watch (04.4).
    */
  def shareOfName(termTokens: Int, nameTokens: Int, config: MatcherConfig = MatcherConfig()): Double =
    if (nameTokens <= 0 || config.lengthDampening <= 0) 1.0
    else math.pow(math.min(1.0, termTokens.toDouble / nameTokens.toDouble), config.lengthDampening)

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
