package demeter.insight

import cats.effect.kernel.MonadCancelThrow
import cats.syntax.all._
import doobie.Transactor
import io.circe.Decoder
import io.circe.generic.semiauto.deriveDecoder

import demeter.foundations.{DealWatchError, MerchantId, Money}
import demeter.watchlist.{DoobieWatchStore, WatchId, WatchItem}

/** What the UI may send. Deliberately not `WatchItem` itself: that type is
  * already valid by construction, so accepting it directly would mean the
  * request had to be valid before it could be parsed.
  */
final case class WatchRequest(
    id: String,
    label: String,
    terms: List[String],
    excludeTerms: Option[List[String]],
    merchantIds: Option[List[Int]],
    maxPriceCents: Option[Long],
    requireSale: Option[Boolean],
    minDiscountPct: Option[Int],
    active: Option[Boolean],
)

object WatchRequest {
  implicit val decoder: Decoder[WatchRequest] = deriveDecoder
}

/** Writes to `watch_item`, and nothing else.
  *
  * The connection this uses belongs to `demeter_watch`, a role granted INSERT,
  * UPDATE and DELETE on that one table. Everything else -- the price history,
  * the raw archive, the alert ledger -- stays unwritable, because those cannot
  * be re-entered and a watchlist can.
  */
trait WatchWrites[F[_]] {
  def save(request: WatchRequest): F[Either[String, Unit]]
  def setActive(id: String, isActive: Boolean): F[Either[String, Boolean]]
  def delete(id: String): F[Either[String, Boolean]]
}

final class DbWatchWrites[F[_]: MonadCancelThrow](xa: Transactor[F]) extends WatchWrites[F] {

  private val store = new DoobieWatchStore[F](xa)

  def save(request: WatchRequest): F[Either[String, Unit]] =
    // Validated by the domain, not here. A watch this module accepted but
    // WatchItem.of would reject is a watch the daily run silently drops.
    WatchItem.of(
      id = WatchId(request.id.trim),
      label = request.label,
      terms = request.terms,
      excludeTerms = request.excludeTerms.getOrElse(Nil),
      merchants = request.merchantIds.getOrElse(Nil).map(MerchantId.apply).toSet,
      maxPrice = request.maxPriceCents.map(c => Money.cents(c)),
      requireSale = request.requireSale.getOrElse(true),
      minDiscountPct = request.minDiscountPct,
      active = request.active.getOrElse(true),
    ) match {
      case Left(invalid) => MonadCancelThrow[F].pure(Left(explain(invalid)))
      case Right(watch) =>
        if (watch.id.value.isEmpty) MonadCancelThrow[F].pure(Left("id must not be blank"))
        else store.upsert(watch).map(_.left.map(describe))
    }

  def setActive(id: String, isActive: Boolean): F[Either[String, Boolean]] =
    store.setActive(WatchId(id), isActive).map(_.left.map(describe))

  def delete(id: String): F[Either[String, Boolean]] =
    store.delete(WatchId(id)).map(_.left.map(describe))

  /** The domain's rejections, in words a person editing a form can act on. */
  private def explain(invalid: WatchItem.InvalidWatch): String = invalid match {
    case WatchItem.InvalidWatch.NoTerms    => "a watch needs at least one term to match on"
    case WatchItem.InvalidWatch.EmptyLabel => "a watch needs a label"
    case WatchItem.InvalidWatch.BadDiscount(pct) =>
      s"minimum discount must be between 1 and 100, not $pct"
    case WatchItem.InvalidWatch.TermAlsoExcluded(term) =>
      s"'$term' is both a term and an exclusion, so this watch could never match anything"
  }

  private def describe(error: DealWatchError): String = error.toString
}
