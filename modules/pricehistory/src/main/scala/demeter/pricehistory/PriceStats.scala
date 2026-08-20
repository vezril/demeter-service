package demeter.pricehistory

import java.time.{Duration, Instant}

import scala.math.BigDecimal.RoundingMode

import demeter.foundations._

/** Spec 07.2 — the statistics that make a verdict possible.
  *
  * Pure functions over a list of observations. Only priced observations
  * contribute numerically; price-absent promos are counted in `n` for context
  * but never move the median. Weighting is pinned and tested — it is exactly
  * the thing a mutation would silently corrupt.
  */
final case class PriceStats(
    key: ProductKey,
    window: Duration,
    n: Int,          // every observation in the window, priced or not
    pricedN: Int,    // the subset the numeric stats are computed from
    weightedMedian: Option[Money],
    min: Option[Money],
    max: Option[Money],
    lastSeen: Option[Money],
)

/** Provenance of a history row: our own observation, or seeded Hammer baseline
  * (07.1), whose fuzzy-matched vendors weigh less.
  */
sealed abstract class Provenance extends Product with Serializable

object Provenance {
  case object FirstParty   extends Provenance
  case object Hammer       extends Provenance
  case object HammerFuzzy  extends Provenance
}

final case class HistoryPoint(observation: PriceObservation, provenance: Provenance = Provenance.FirstParty)

object RollingStats {

  /** Pinned weights (07.2). A scalar/High observation counts full; derived
    * prices count less; free-text-scraped and fuzzy-matched Hammer rows least.
    */
  def weightOf(point: HistoryPoint): Double = {
    val basisWeight = point.observation.priceBasis match {
      case PriceBasis.ScalarPrice       => 1.0
      case PriceBasis.MultiBuyUnit      => 0.7
      case PriceBasis.ParsedFromText    => 0.4
      case PriceBasis.PercentOffUnknown => 0.0 // no price to contribute anyway
      case PriceBasis.Unknown           => 0.0
    }
    // priceConfidence, deliberately: how well we could split a bilingual product
    // name has no bearing on whether its price is trustworthy
    val confidenceWeight = point.observation.priceConfidence match {
      case Confidence.High   => 1.0
      case Confidence.Medium => 0.7
      case Confidence.Low    => 0.4
    }
    val provenanceWeight = point.provenance match {
      case Provenance.FirstParty  => 1.0
      case Provenance.Hammer      => 0.8
      case Provenance.HammerFuzzy => 0.4
    }
    basisWeight * confidenceWeight * provenanceWeight
  }

  def rollingStats(key: ProductKey, points: List[HistoryPoint], window: Duration, now: Instant): PriceStats = {
    val cutoff   = now.minus(window)
    val inWindow = points.filter(p => !p.observation.observedAt.isBefore(cutoff))
    val priced = inWindow.flatMap(p => p.observation.effectivePrice.map(m => (m.cents, weightOf(p)))).filter(_._2 > 0)

    PriceStats(
      key = key,
      window = window,
      n = inWindow.size,
      pricedN = priced.size,
      weightedMedian = weightedMedian(priced).map(Money.cents(_)),
      min = priced.map(_._1).minOption.map(Money.cents(_)),
      max = priced.map(_._1).maxOption.map(Money.cents(_)),
      lastSeen = inWindow
        .sortBy(_.observation.observedAt)
        .lastOption
        .flatMap(_.observation.effectivePrice),
    )
  }

  /** Weighted median with a pinned interpolation rule so results are
    * deterministic on small weighted samples: sort by value, walk the
    * cumulative weight, and take the first value whose cumulative weight
    * reaches half the total. When it lands exactly on the boundary, average
    * with the next distinct value (half-even on the cent).
    */
  def weightedMedian(valuesWithWeights: List[(Long, Double)]): Option[Long] =
    if (valuesWithWeights.isEmpty) None
    else {
      val sorted = valuesWithWeights.sortBy(_._1)
      val total  = sorted.map(_._2).sum
      val half   = total / 2.0
      val epsilon = 1e-9

      var cumulative = 0.0
      var result: Option[Long] = None
      var i = 0
      while (i < sorted.length && result.isEmpty) {
        val (value, weight) = sorted(i)
        cumulative += weight
        if (math.abs(cumulative - half) < epsilon && i + 1 < sorted.length) {
          // exact boundary: average with the next value, half-even to the cent
          val next = sorted(i + 1)._1
          result = Some((BigDecimal(value + next) / 2).setScale(0, RoundingMode.HALF_EVEN).toLongExact)
        } else if (cumulative >= half - epsilon) {
          result = Some(value)
        }
        i += 1
      }
      result.orElse(sorted.lastOption.map(_._1))
    }
}
