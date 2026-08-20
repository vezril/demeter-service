package demeter.foundations

import java.time.Instant

/** Spec 00.4 — the nouns every other context passes around.
  *
  * Types only; behaviour (parsing, key derivation, precedence) lives in the
  * contexts that own it. Absence is modelled honestly: a missing price is an
  * `Option`, never a sentinel.
  */
final case class MerchantId(value: Int)  extends AnyVal
final case class FlyerId(value: Long)    extends AnyVal

/** Our own stable key (derived in 02.7); opaque here. */
final case class ProductKey(value: String) extends AnyVal

final case class Merchant(id: MerchantId, name: String)

/** Standard comparison units for unit pricing (02.4 computes; 00 owns the type). */
sealed abstract class StdUnit extends Product with Serializable

object StdUnit {
  case object PerLitre extends StdUnit
  case object PerKg    extends StdUnit
  case object PerItem  extends StdUnit
}

final case class Size(quantity: BigDecimal, unit: StdUnit, packCount: Int)
final case class UnitPrice(price: Money, per: StdUnit)

sealed abstract class PriceBasis extends Product with Serializable

object PriceBasis {
  case object ScalarPrice       extends PriceBasis // upstream gave a clean current_price
  case object MultiBuyUnit      extends PriceBasis // derived from "N for $X"
  case object PercentOffUnknown extends PriceBasis // "% off" but base unknown -> price None
  case object ParsedFromText    extends PriceBasis // pulled a "$X" out of free text
  case object Unknown           extends PriceBasis
}

sealed abstract class Confidence(private val rank: Int) extends Product with Serializable {
  def min(that: Confidence): Confidence = if (rank <= that.rank) this else that
}

object Confidence {
  case object Low    extends Confidence(0)
  case object Medium extends Confidence(1)
  case object High   extends Confidence(2)
}

final case class Flyer private (
    id: FlyerId,
    merchantId: MerchantId,
    name: String,
    validFrom: Instant,
    validTo: Instant,
    postalCode: PostalCode,
    locale: Locale,
)

object Flyer {

  /** `validFrom < validTo` is a hard domain invariant (00.4). */
  def of(
      id: FlyerId,
      merchantId: MerchantId,
      name: String,
      validFrom: Instant,
      validTo: Instant,
      postalCode: PostalCode,
      locale: Locale,
  ): Either[DealWatchError.InvalidDomain, Flyer] =
    if (validFrom.isBefore(validTo))
      Right(Flyer(id, merchantId, name, validFrom, validTo, postalCode, locale))
    else
      Left(
        DealWatchError.InvalidDomain(
          what = s"Flyer(${id.value})",
          reason = s"non-positive validity window: $validFrom >= $validTo",
        )
      )
}

/** One priced (or price-unknown) line item as it appeared in a flyer.
  * `sourceItemId` is NOT stable across weeks (hence ProductKey, 02.7).
  * `rawName` is the audit trail — preserved verbatim, never modified.
  */
final case class FlyerItem(
    sourceItemId: String,
    flyerId: FlyerId,
    merchantId: MerchantId,
    name: BilingualText,
    rawName: String,
    currentPrice: Option[Money],
    originalPrice: Option[Money],
    saleStory: Option[String],
    validFrom: Instant,
    validTo: Instant,
)

/** The normalized, storable fact. Product of 02.6. */
final case class PriceObservation(
    productKey: ProductKey,
    merchantId: MerchantId,
    flyerId: FlyerId,
    observedAt: Instant,
    name: BilingualText,
    rawName: String,
    effectivePrice: Option[Money],
    priceBasis: PriceBasis,
    originalPrice: Option[Money],
    size: Option[Size],           // extracted by 02.4; feeds the product dimension (03.1)
    unitPrice: Option[UnitPrice],
    saleText: Option[String],
    validFrom: Instant,
    validTo: Instant,
    confidence: Confidence,
)
