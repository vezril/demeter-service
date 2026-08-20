package demeter.enrichment

import java.time.Instant

import demeter.foundations._

/** Spec 06.1 — retailer-direct sources that supply the REGULAR price Flipp
  * rarely gives, so "is this a deal?" has both halves.
  *
  * Distinct from FlyerSource (01.1): the job is different (given a product
  * query, return current shelf/online prices). Enrichment is best-effort and
  * advisory — a missing enrichment never blocks an observation or an alert, it
  * only sharpens the verdict (07.3). Anonymous / static-key endpoints only:
  * never logged-in, account-specific data.
  */
sealed abstract class PriceProvenance extends Product with Serializable

object PriceProvenance {

  /** In-store / shelf price for the queried location. */
  case object Shelf extends PriceProvenance

  /** Online price, which can differ from the in-store flyer price (06.3). */
  case object OnlineReference extends PriceProvenance
}

final case class EnrichedPrice(
    merchantId: MerchantId,
    name: BilingualText,
    regularPrice: Option[Money],
    salePrice: Option[Money],
    unitPrice: Option[UnitPrice],
    source: SourceName,
    provenance: PriceProvenance,
    fetchedAt: Instant,
)

trait EnrichmentSource[F[_]] {
  def name: SourceName
  def merchantsCovered: Set[MerchantId]
  def lookup(query: String, near: PostalCode, locale: Locale): F[Either[DealWatchError, List[EnrichedPrice]]]
}
