package demeter.watchlist

import cats.data.NonEmptyList
import demeter.foundations.{MerchantId, Money}

/** Spec 04.1 — what a user watch entry is: the thing you're looking for, the
  * conditions under which it counts as a hit, and the terms it matches on.
  * Terms are stored raw (any language); normalization happens at match time
  * (04.2) so users can type "café" or "Coca-Cola" naturally. The alerting
  * conditions are held and validated here but evaluated in 05.1.
  */
final case class WatchId(value: String) extends AnyVal

final case class WatchItem(
    id: WatchId,
    label: String,
    terms: NonEmptyList[String],
    merchants: Set[MerchantId],      // empty = any merchant
    maxPrice: Option[Money],
    requireSale: Boolean,
    minDiscountPct: Option[Int],
    active: Boolean,
) {
  def inScope(merchant: MerchantId): Boolean = merchants.isEmpty || merchants(merchant)
}

object WatchItem {

  sealed abstract class InvalidWatch extends Product with Serializable
  object InvalidWatch {
    case object NoTerms      extends InvalidWatch
    case object EmptyLabel   extends InvalidWatch
    final case class BadDiscount(pct: Int) extends InvalidWatch
  }

  def of(
      id: WatchId,
      label: String,
      terms: List[String],
      merchants: Set[MerchantId] = Set.empty,
      maxPrice: Option[Money] = None,
      requireSale: Boolean = false,
      minDiscountPct: Option[Int] = None,
      active: Boolean = true,
  ): Either[InvalidWatch, WatchItem] = {
    val cleaned = terms.map(_.trim).filter(_.nonEmpty)
    for {
      nel <- NonEmptyList.fromList(cleaned).toRight(InvalidWatch.NoTerms)
      _   <- Either.cond(label.trim.nonEmpty, (), InvalidWatch.EmptyLabel)
      _ <- minDiscountPct match {
        case Some(p) if p < 1 || p > 100 => Left(InvalidWatch.BadDiscount(p))
        case _                           => Right(())
      }
    } yield WatchItem(id, label.trim, nel, merchants, maxPrice, requireSale, minDiscountPct, active)
  }
}
