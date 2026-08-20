package demeter.alerting

import java.time.Instant

import demeter.foundations.{Money, ProductKey}
import demeter.watchlist.WatchId

/** Spec 05.2 — stop the same deal alerting every day for the week a flyer runs.
  *
  * Pure decision against prior-alert state passed in; persistence is the sink's
  * concern (05.4). Key = watch + product + the flyer's validity window, so:
  *  - the same deal in the same window is suppressed after the first send;
  *  - a new window for the same product is news again;
  *  - a price DROP within the window re-alerts (the deal got better);
  *  - a price rise does not.
  */
final case class AlertKey(watchId: WatchId, productKey: ProductKey, windowFrom: Instant, windowTo: Instant)

/** What was last sent for a key — the price is what makes "it got better" decidable. */
final case class AlertRecord(key: AlertKey, alertedPrice: Option[Money], alertedAt: Instant)

object AlertDedup {

  def keyOf(deal: Deal): AlertKey =
    AlertKey(deal.watch.id, deal.observation.productKey, deal.observation.validFrom, deal.observation.validTo)

  def isNew(deal: Deal, alreadyAlerted: Map[AlertKey, AlertRecord]): Boolean =
    alreadyAlerted.get(keyOf(deal)) match {
      case None => true // never alerted for this watch+product+window
      case Some(previous) =>
        (deal.observation.effectivePrice, previous.alertedPrice) match {
          case (Some(now), Some(before)) => now.cents < before.cents // improved deal
          case _                         => false                    // no price to compare: already told you
        }
    }

  def record(deal: Deal, at: Instant): AlertRecord =
    AlertRecord(keyOf(deal), deal.observation.effectivePrice, at)
}
