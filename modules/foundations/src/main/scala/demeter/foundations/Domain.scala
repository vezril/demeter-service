package demeter.foundations

import java.time.Instant

/** Spec 00.4 — the nouns every other context passes around.
  *
  * Types only; behaviour (parsing, key derivation, precedence) lives in the
  * contexts that own it. Absence is modelled honestly: a missing price is an
  * `Option`, never a sentinel.
  */
final case class MerchantId(value: Int) extends AnyVal
final case class FlyerId(value: Long)   extends AnyVal

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
    size: Option[Size], // extracted by 02.4; feeds the product dimension (03.1)
    unitPrice: Option[UnitPrice],
    saleText: Option[String],
    validFrom: Instant,
    validTo: Instant,
    /** How much the PRICE can be trusted: where on 02.6's derivation ladder it
      * came from. This is what weights price history (07.2).
      */
    priceConfidence: Confidence,
    /** How well the item's IDENTITY was resolved — the bilingual name split and
      * size extraction. This is what should temper MATCHING (04.4), and it says
      * nothing about whether the price is right.
      */
    matchConfidence: Confidence,
) {

  /** The old combined value, kept derivable for anything that genuinely wants
    * "how much do we trust this row overall". It is deliberately NOT what
    * weights price history: a clean scalar price is a clean scalar price even
    * when we cannot tell what language its name is in.
    */
  def confidence: Confidence = priceConfidence.min(matchConfidence)

  /** Same stored row, by the triple 03.1 makes unique. Used to keep an
    * observation out of the history it is being judged against.
    */
  def sameRecordAs(other: PriceObservation): Boolean =
    productKey == other.productKey && flyerId == other.flyerId && observedAt == other.observedAt
}
