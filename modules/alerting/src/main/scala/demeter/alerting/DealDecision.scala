package demeter.alerting

import demeter.foundations._
import demeter.pricehistory.{DealScorer, DealVerdict}
import demeter.watchlist.{Match, WatchItem}

/** Spec 05.1 — the pure decision that turns a match into an alertable deal.
  *
  * A match means "this watched thing is in a flyer"; a deal means "and it's
  * actually worth telling you about". Gate order is pinned: maxPrice ->
  * requireSale -> minDiscountPct. Every suppression carries a reason for
  * diagnostics (08.3).
  */
sealed abstract class AlertDecision extends Product with Serializable

object AlertDecision {
  final case class Alert(deal: Deal)                extends AlertDecision
  final case class Suppress(reason: SuppressReason) extends AlertDecision
}

sealed abstract class SuppressReason(val message: String) extends Product with Serializable

object SuppressReason {
  case object AboveMaxPrice      extends SuppressReason("above max price")
  case object PriceUnknown       extends SuppressReason("price unknown, max price required")
  case object NotASale           extends SuppressReason("not a sale")
  case object DiscountTooShallow extends SuppressReason("discount below threshold")
  case object WatchInactive      extends SuppressReason("watch inactive")
}

/** An alertable deal: the match, why it's good, and how strongly it scored. */
final case class Deal(
    watch: WatchItem,
    observation: PriceObservation,
    verdict: DealVerdict,
    score: Double,
)

object DealDecision {

  def decide(m: Match, verdict: DealVerdict, enrichedRegular: Option[Money] = None): AlertDecision = {
    val watch = m.watch
    val obs   = m.observation

    if (!watch.active) AlertDecision.Suppress(SuppressReason.WatchInactive)
    else
      maxPriceGate(watch, obs)
        .orElse(requireSaleGate(watch, verdict))
        .orElse(discountGate(watch, obs, enrichedRegular))
        .map(AlertDecision.Suppress.apply)
        .getOrElse(AlertDecision.Alert(Deal(watch, obs, verdict, m.score.combined)))
  }

  /** 1. A price above maxPrice never alerts. A price-absent promo passes this
    * gate only when no maxPrice is set — we won't guess that an unknown price
    * clears a ceiling.
    */
  private def maxPriceGate(watch: WatchItem, obs: PriceObservation): Option[SuppressReason] =
    (watch.maxPrice, obs.effectivePrice) match {
      case (Some(max), Some(price)) if price.cents > max.cents => Some(SuppressReason.AboveMaxPrice)
      case (Some(_), None)                                     => Some(SuppressReason.PriceUnknown)
      case _                                                   => None
    }

  /** 2. requireSale demands a verdict of at least Notable — a price at or above
    * its own history is not a sale even when it's under maxPrice.
    */
  private def requireSaleGate(watch: WatchItem, verdict: DealVerdict): Option[SuppressReason] =
    if (watch.requireSale && !verdict.isNotableOrBetter) Some(SuppressReason.NotASale) else None

  /** 3. minDiscountPct gates on depth from the best baseline available:
    * enrichment regular (06) beats the flyer's own claimed original.
    */
  private def discountGate(
      watch: WatchItem,
      obs: PriceObservation,
      enrichedRegular: Option[Money],
  ): Option[SuppressReason] =
    watch.minDiscountPct.flatMap { required =>
      val baseline = enrichedRegular.orElse(obs.originalPrice)
      (obs.effectivePrice, baseline) match {
        case (Some(price), Some(base)) =>
          if (DealScorer.percentBelow(price, base) >= required) None
          else Some(SuppressReason.DiscountTooShallow)
        case _ => Some(SuppressReason.DiscountTooShallow) // can't prove the depth -> don't alert
      }
    }
}
