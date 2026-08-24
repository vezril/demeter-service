package demeter.watchlist

import demeter.foundations.{Confidence, Money, PriceObservation}

/** Spec 04.4 — attach a quality score to each match so alerting can pick the
  * best when several observations match one watch item (common: many flyers
  * carry milk) and suppress the rest.
  *
  * Weights are config, not magic constants (04.4 / 08.4).
  */
final case class ScoringWeights(text: Double = 0.4, confidence: Double = 0.3, price: Double = 0.3) {
  require(text >= 0 && confidence >= 0 && price >= 0, "weights must be non-negative")
  private val total: Double = text + confidence + price
  require(total > 0, "weights must not all be zero")

  def normalized: ScoringWeights = ScoringWeights(text / total, confidence / total, price / total)
}

final case class MatchScore(textScore: Double, confidence: Confidence, priceRank: Double, weights: ScoringWeights) {

  /** Weighted, documented, and tested; always within 0..1. */
  def combined: Double = {
    val w = weights.normalized
    w.text * textScore + w.confidence * MatchScore.confidenceScore(confidence) + w.price * priceRank
  }
}

final case class Match(watch: WatchItem, observation: PriceObservation, score: MatchScore)

object MatchScore {

  def confidenceScore(c: Confidence): Double = c match {
    case Confidence.High   => 1.0
    case Confidence.Medium => 0.6
    case Confidence.Low    => 0.2
  }

  /** Reserved for price-absent observations, so a known price — however dear —
    * always outranks "we couldn't price this" (04.4: unknown ranks last).
    */
  val UnknownPriceRank = 0.0
  val KnownPriceFloor  = 0.1

  /** Rank a group of matched observations for one watch item on price: the
    * cheapest scores 1.0, the dearest known price scores [[KnownPriceFloor]],
    * and a price-absent observation scores [[UnknownPriceRank]] — strictly last.
    */
  def rankByPrice(prices: List[Option[Money]]): List[Double] = {
    val known = prices.flatten.map(_.cents)
    if (known.isEmpty) prices.map(_ => UnknownPriceRank)
    else {
      val min  = known.min
      val max  = known.max
      val span = 1.0 - KnownPriceFloor
      prices.map {
        case None => UnknownPriceRank
        case Some(m) =>
          if (max == min) 1.0
          else KnownPriceFloor + span * (1.0 - (m.cents - min).toDouble / (max - min).toDouble)
      }
    }
  }

  /** Score every observation matched to one watch item, price-ranked within the group. */
  def scoreGroup(
      watch: WatchItem,
      matched: List[(PriceObservation, TextMatch)],
      weights: ScoringWeights = ScoringWeights(),
  ): List[Match] = {
    val ranks = rankByPrice(matched.map(_._1.effectivePrice))
    matched.zip(ranks).map { case ((obs, tm), rank) =>
      // matchConfidence: ranking one match above another is a question about how
      // well each item was IDENTIFIED, not about price derivation
      Match(watch, obs, MatchScore(tm.textScore, obs.matchConfidence, rank, weights))
    }
  }
}
