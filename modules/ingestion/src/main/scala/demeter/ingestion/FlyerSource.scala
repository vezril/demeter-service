package demeter.ingestion

import demeter.foundations._

/** Spec 01.1 — the seam that makes the whole system survivable.
  *
  * Every flyer provider implements this one interface; the orchestrator depends
  * on it, never on Flipp directly. Failure is a value (`Either[DealWatchError, _]`),
  * never a thrown exception. Results carry the archived raw bytes alongside the
  * parsed shape so persistence (03.2) can archive before anything trusts the parse.
  * (`SourceName` and `RawResponse` live in foundations — persistence shares them.)
  */
sealed abstract class Capability extends Product with Serializable

object Capability {
  case object Flyers extends Capability
  case object Items  extends Capability
  case object Search extends Capability
}

final case class RawFlyerListing(
    raw: RawResponse,
    flyers: List[Flyer],
    merchants: List[Merchant], // merchant names ride on the flyer objects here (01.2) — capture now, save a join later
    dropped: Int,              // individual flyer entries that failed to decode (drop-and-count)
)

final case class RawFlyerItems(
    raw: RawResponse,
    items: List[FlyerItem],
    dropped: Int, // feeds the drift alarm (08.3)
)

/** E-commerce listings have a different shape and different meaning than paper-flyer
  * items (01.3); a distinct type enforces that they are never merged naively.
  */
final case class EcomItem(
    sourceItemId: String,
    merchantId: MerchantId,
    merchantName: String,
    rawName: String,
    currentPrice: Option[Money],
    originalPrice: Option[Money],
)

final case class RawSearchResult(
    raw: RawResponse,
    flyerItems: List[FlyerItem],
    ecomItems: List[EcomItem],
    merchants: List[Merchant], // resolved from items[].merchant_name, populated in search responses
    normalizedQuery: Option[String],
    dropped: Int,
)

trait FlyerSource[F[_]] {
  def name: SourceName

  /** Honest advertisement of what this source supports; calling an unsupported
    * capability returns `Left(Unsupported)` and performs no network call.
    */
  def capabilities: Set[Capability]

  /** Flyers currently active for a postal code. */
  def flyers(postal: PostalCode, locale: Locale): F[Either[DealWatchError, RawFlyerListing]]

  /** All items within one flyer. */
  def items(flyerId: FlyerId, postal: PostalCode, locale: Locale): F[Either[DealWatchError, RawFlyerItems]]

  /** Cross-merchant item search (optional capability). */
  def search(term: String, postal: PostalCode, locale: Locale): F[Either[DealWatchError, RawSearchResult]]
}
