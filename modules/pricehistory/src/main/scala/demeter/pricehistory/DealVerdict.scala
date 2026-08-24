package demeter.pricehistory

import demeter.foundations._

/** Spec 07.3 — turn stats + the current observation + any enrichment into the
  * human verdict that makes an alert worth reading. Pure judgement; the
  * notification is 05's job.
  */
sealed abstract class DealVerdict extends Product with Serializable {

  /** Ordering used by 05.1's `requireSale` gate: anything at least Notable is a real sale. */
  def isNotableOrBetter: Boolean = this match {
    case DealVerdict.BestEver(_)    => true
    case DealVerdict.BelowUsual(_)  => true
    case DealVerdict.Notable        => true
    case DealVerdict.AtOrAboveUsual => false
    case DealVerdict.Unknown        => false
  }

  def phrase: String = this match {
    case DealVerdict.BestEver(weeks) => s"cheapest in $weeks weeks"
    case DealVerdict.BelowUsual(pct) => s"$pct% below usual"
    case DealVerdict.Notable         => "a real sale"
    case DealVerdict.AtOrAboveUsual  => "not actually a deal"
    case DealVerdict.Unknown         => "no price history yet"
  }
}

object DealVerdict {
  final case class BestEver(sinceWeeks: Int)       extends DealVerdict
  final case class BelowUsual(pctBelowMedian: Int) extends DealVerdict
  case object Notable                              extends DealVerdict
  case object AtOrAboveUsual                       extends DealVerdict
  case object Unknown                              extends DealVerdict
}

/** Thresholds are config (08.4), not magic constants. */
final case class DealThresholds(
    belowUsualPct: Int = 10, // at least this far under the median to count as BelowUsual
    minHistoryN: Int = 4,    // below this, never claim BestEver (don't over-claim on two points)
)

object DealScorer {

  /** Enrichment-supplied regular price (06) overrides an inflated flyer
    * "regular" when computing discount depth. Kept as a minimal structural
    * type so pricehistory need not depend on the enrichment module.
    */
  final case class EnrichmentBaseline(regularPrice: Option[Money])

  def scoreDeal(
      obs: PriceObservation,
      stats: PriceStats,
      enrichment: Option[EnrichmentBaseline] = None,
      thresholds: DealThresholds = DealThresholds(),
  ): DealVerdict =
    obs.effectivePrice match {
      case None => DealVerdict.Unknown
      case Some(price) =>
        val enrichedRegular = enrichment.flatMap(_.regularPrice)
        // A flyer's own struck-through regular is the weakest baseline; enrichment
        // beats it, history beats nothing (07.3 / 06.4).
        val baseline = enrichedRegular.orElse(stats.weightedMedian)

        baseline match {
          case None => DealVerdict.Unknown
          case Some(base) =>
            val thinHistory = stats.pricedN < thresholds.minHistoryN
            val pctBelow    = percentBelow(price, base)
            // Enrichment supplies a retailer's actual regular price, which is a
            // real baseline regardless of how much history we hold. Without it,
            // a thin sample cannot support ANY confident claim — including a
            // negative one.
            val canJudgeOnThinHistory = enrichedRegular.isDefined

            if (!thinHistory && stats.min.exists(price.cents <= _.cents))
              DealVerdict.BestEver(weeksOf(stats))
            else if (pctBelow >= thresholds.belowUsualPct)
              if (thinHistory && !canJudgeOnThinHistory) DealVerdict.Notable
              else DealVerdict.BelowUsual(pctBelow)
            // "not actually a deal" is a claim, and one or two observations
            // cannot support it. Saying Unknown here is what keeps requireSale
            // from silently suppressing every alert for the weeks it takes
            // history to accumulate.
            else if (thinHistory && !canJudgeOnThinHistory) DealVerdict.Unknown
            else if (price.cents >= base.cents) DealVerdict.AtOrAboveUsual
            else DealVerdict.Notable
        }
    }

  /** Discount depth against a baseline, floored at 0 (a price above baseline is not a discount). */
  def percentBelow(price: Money, baseline: Money): Int =
    if (baseline.cents <= 0) 0
    else math.max(0, ((baseline.cents - price.cents) * 100.0 / baseline.cents).round.toInt)

  private def weeksOf(stats: PriceStats): Int =
    math.max(1, (stats.window.toDays / 7).toInt)
}
