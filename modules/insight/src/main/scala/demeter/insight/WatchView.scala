package demeter.insight

import java.time.Instant

import io.circe.Encoder
import io.circe.generic.semiauto.deriveEncoder

/** A configured watch, with what it has actually produced.
  *
  * NOT included, deliberately: how many observations this watch matched and why
  * those matches were suppressed. That is the question the "watch health" view
  * exists to answer, and the data does not exist -- the run report records
  * suppression by reason across the whole run, not per watch. Serving a plausible
  * approximation would be worse than serving nothing, because tuning a watch on
  * invented numbers produces a watch tuned to nothing.
  */
final case class WatchView(
    id: String,
    label: String,
    terms: List[String],
    excludeTerms: List[String],
    merchantIds: List[Int],
    maxPriceCents: Option[Long],
    requireSale: Boolean,
    minDiscountPct: Option[Int],
    active: Boolean,
    /** From the alert ledger: what this watch has actually sent. */
    alertsSent: Int,
    lastAlertedAt: Option[Instant],
)

/** One alert as sent. */
final case class AlertView(
    watchId: String,
    watchLabel: Option[String],
    productKey: String,
    /** Resolved by joining the observations: ProductKey is merchant-scoped, so
      * one key means one merchant. Verified against the live data (no key maps
      * to more than one merchant), not assumed from the naming.
      */
    merchantId: Option[Int],
    merchantName: Option[String],
    itemName: Option[String],
    alertedCents: Option[Long],
    alertedAt: Instant,
    windowFrom: Instant,
    windowTo: Instant,
)

object WatchView {
  implicit val encoder: Encoder[WatchView] = deriveEncoder
}

object AlertView {
  implicit val encoder: Encoder[AlertView] = deriveEncoder
}
