package demeter.insight

import java.time.Instant

import io.circe.{Encoder, Json}
import io.circe.syntax._

import demeter.foundations.{Confidence, PriceObservation}
import demeter.pricehistory.{DealVerdict, PriceStats}

/** One observation as a chart point.
  *
  * Confidence travels per point rather than per series, because a chart that
  * draws a Low-confidence price parsed out of free text identically to a scalar
  * price is actively misleading: the median it suggests to the eye is not the
  * median the weighting actually produced.
  */
final case class HistoryPointView(
    observedAt: Instant,
    merchantId: Int,
    priceCents: Option[Long],
    unitCents: Option[Long],
    unitBasis: Option[String],
    priceBasis: String,
    confidence: String,
    saleText: Option[String],
    validFrom: Instant,
    validTo: Instant,
)

final case class HistoryView(
    productKey: String,
    windowDays: Long,
    points: List[HistoryPointView],
    stats: StatsView,
    verdict: Option[String],
)

/** The rolling distribution, computed by `pricehistory` rather than here. */
final case class StatsView(
    n: Int,
    pricedN: Int,
    weightedMedianCents: Option[Long],
    minCents: Option[Long],
    maxCents: Option[Long],
    /** The most recent PRICE in the window, not a timestamp -- PriceStats.lastSeen
      * is a Money. Named in cents here so the API cannot be misread as a date.
      */
    lastSeenCents: Option[Long],
)

object HistoryView {

  def pointOf(o: PriceObservation): HistoryPointView =
    HistoryPointView(
      observedAt = o.observedAt,
      merchantId = o.merchantId.value,
      priceCents = o.effectivePrice.map(_.cents),
      unitCents = o.unitPrice.map(_.price.cents),
      unitBasis = o.unitPrice.map(_.per.toString),
      priceBasis = o.priceBasis.toString,
      confidence = label(o.priceConfidence),
      saleText = o.saleText,
      validFrom = o.validFrom,
      validTo = o.validTo,
    )

  def statsOf(stats: PriceStats): StatsView =
    StatsView(
      n = stats.n,
      pricedN = stats.pricedN,
      weightedMedianCents = stats.weightedMedian.map(_.cents),
      minCents = stats.min.map(_.cents),
      maxCents = stats.max.map(_.cents),
      lastSeenCents = stats.lastSeen.map(_.cents),
    )

  private def label(c: Confidence): String = c.toString

  implicit val pointEncoder: Encoder[HistoryPointView] = io.circe.generic.semiauto.deriveEncoder
  implicit val statsEncoder: Encoder[StatsView]        = io.circe.generic.semiauto.deriveEncoder
  implicit val encoder: Encoder[HistoryView] = h =>
    Json.obj(
      "productKey" -> h.productKey.asJson,
      "windowDays" -> h.windowDays.asJson,
      "points"     -> h.points.asJson,
      "stats"      -> h.stats.asJson,
      "verdict"    -> h.verdict.asJson,
    )

  def verdictLabel(v: DealVerdict): String = v.phrase
}
