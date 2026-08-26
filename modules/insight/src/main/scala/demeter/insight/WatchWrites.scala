package demeter.insight

import cats.effect.kernel.MonadCancelThrow
import cats.syntax.all._
import doobie.Transactor
import org.typelevel.log4cats.Logger
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

/** Why a write did not happen.
  *
  * The two cases want different answers and different fixes. `Invalid` is the
  * person's problem and is actionable — a term that is also an exclusion, a
  * discount out of range. `Unavailable` is the deployment's problem and tells
  * them nothing about their watch. Collapsing both into one string reported a
  * database outage as "your watch is invalid", which sends someone editing a
  * form that was never wrong.
  */
sealed abstract class WriteFailure extends Product with Serializable
object WriteFailure {
  final case class Invalid(reason: String)     extends WriteFailure
  final case class Unavailable(reason: String) extends WriteFailure
}

trait WatchWrites[F[_]] {
  def save(request: WatchRequest): F[Either[WriteFailure, Unit]]
  def setActive(id: String, isActive: Boolean): F[Either[WriteFailure, Boolean]]
  def delete(id: String): F[Either[WriteFailure, Boolean]]
}

/** Writes to `watch_item`, and nothing else.
  *
  * The connection this uses belongs to `demeter_watch`, a role granted INSERT,
  * UPDATE and DELETE on that one table. Everything else -- the price history,
  * the raw archive, the alert ledger -- stays unwritable, because those cannot
  * be re-entered and a watchlist can.
  */
final class DbWatchWrites[F[_]: MonadCancelThrow: Logger](xa: Transactor[F]) extends WatchWrites[F] {

  private val store = new DoobieWatchStore[F](xa)

  def save(request: WatchRequest): F[Either[WriteFailure, Unit]] =
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
      case Left(invalid) => MonadCancelThrow[F].pure(Left(WriteFailure.Invalid(explain(invalid))))
      case Right(watch) =>
        if (watch.id.value.isEmpty)
          MonadCancelThrow[F].pure(Left(WriteFailure.Invalid("id must not be blank")))
        else reporting(store.upsert(watch))
    }

  def setActive(id: String, isActive: Boolean): F[Either[WriteFailure, Boolean]] =
    reporting(store.setActive(WatchId(id), isActive))

  def delete(id: String): F[Either[WriteFailure, Boolean]] =
    reporting(store.delete(WatchId(id)))

  /** The domain's rejections, in words a person editing a form can act on. */
  private def explain(invalid: WatchItem.InvalidWatch): String = invalid match {
    case WatchItem.InvalidWatch.NoTerms    => "a watch needs at least one term to match on"
    case WatchItem.InvalidWatch.EmptyLabel => "a watch needs a label"
    case WatchItem.InvalidWatch.BadDiscount(pct) =>
      s"minimum discount must be between 1 and 100, not $pct"
    case WatchItem.InvalidWatch.TermAlsoExcluded(term) =>
      s"'$term' is both a term and an exclusion, so this watch could never match anything"
  }

  /** Anything the STORE returns is an infrastructure failure -- the watch was
    * already validated by the time it got here -- so it is logged and reported
    * as unavailable.
    *
    * The logging is the point of the flatMap. The response deliberately says
    * only "database unavailable", because the real message names the role and
    * the failure mode and anyone on the tailnet can read it. If it were not
    * written down here it would not be written down anywhere, and an outage
    * would leave no trace but a 503.
    */
  private def reporting[A](result: F[Either[DealWatchError, A]]): F[Either[WriteFailure, A]] =
    result.flatMap {
      case Right(a) => MonadCancelThrow[F].pure(Right(a))
      case Left(error) =>
        Logger[F].error(s"watch write failed: $error").as(Left(WriteFailure.Unavailable(error.toString)))
    }
}
