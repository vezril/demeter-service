package demeter.alerting

import java.time.Instant

import cats.data.NonEmptyList
import demeter.foundations._
import demeter.pricehistory.DealVerdict
import demeter.watchlist._

/** Shared builders for the 05.x suites. */
object AlertingFixtures {

  val from: Instant = Instant.parse("2026-07-23T00:00:00Z")
  val to: Instant   = Instant.parse("2026-07-30T00:00:00Z")

  def watch(
      maxPrice: Option[Long] = None,
      requireSale: Boolean = false,
      minDiscountPct: Option[Int] = None,
      active: Boolean = true,
      id: String = "w1",
  ): WatchItem =
    WatchItem(
      id = WatchId(id),
      label = "Milk 4L",
      terms = NonEmptyList.of("milk", "lait"),
      merchants = Set.empty,
      maxPrice = maxPrice.map(Money.cents(_)),
      requireSale = requireSale,
      minDiscountPct = minDiscountPct,
      active = active,
    )

  def obs(
      cents: Option[Long] = Some(250L),
      original: Option[Long] = None,
      saleText: Option[String] = None,
      key: String = "v1:k",
      validFrom: Instant = from,
      validTo: Instant = to,
  ): PriceObservation =
    PriceObservation(
      productKey = ProductKey(key),
      merchantId = MerchantId(100),
      flyerId = FlyerId(900L),
      observedAt = from,
      name = BilingualText(Some("Lait Natrel 4 L"), Some("Natrel Milk 4 L")),
      rawName = "Lait Natrel 4 L | Natrel Milk 4 L",
      effectivePrice = cents.map(Money.cents(_)),
      priceBasis = if (cents.isEmpty) PriceBasis.PercentOffUnknown else PriceBasis.ScalarPrice,
      originalPrice = original.map(Money.cents(_)),
      size = None,
      unitPrice = None,
      saleText = saleText,
      validFrom = validFrom,
      validTo = validTo,
      confidence = Confidence.High,
    )

  def matched(w: WatchItem, o: PriceObservation, textScore: Double = 1.0): Match =
    Match(w, o, MatchScore(textScore, o.confidence, 1.0, ScoringWeights()))

  def deal(
      w: WatchItem = watch(),
      o: PriceObservation = obs(),
      verdict: DealVerdict = DealVerdict.BelowUsual(15),
      score: Double = 0.9,
  ): Deal = Deal(w, o, verdict, score)
}
