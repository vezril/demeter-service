package demeter.watchlist

import cats.data.NonEmptyList
import demeter.foundations.{MerchantId, Money}
import demeter.normalization.TextNormalizer

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
    /** Terms that VETO a match. "butter" catches peanut butter, butter
      * croissants, and Butter Chicken; no price ceiling separates those, because
      * peanut butter is cheaper than butter. Matched by the same rules as terms,
      * so plurals ("arachide"/"arachides") are caught without a second entry.
      */
    excludeTerms: List[String],
    merchants: Set[MerchantId], // empty = any merchant
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
    case object NoTerms                    extends InvalidWatch
    case object EmptyLabel                 extends InvalidWatch
    final case class BadDiscount(pct: Int) extends InvalidWatch

    /** A term that is also excluded can never match anything. Silently accepting
      * it produces a watch that looks configured and is permanently inert.
      */
    final case class TermAlsoExcluded(term: String) extends InvalidWatch
  }

  def of(
      id: WatchId,
      label: String,
      terms: List[String],
      excludeTerms: List[String] = Nil,
      merchants: Set[MerchantId] = Set.empty,
      maxPrice: Option[Money] = None,
      // Default ON: without it every match alerts. A first real run produced
      // 384 alerts from three watches, which is the "you mute it in a week"
      // outcome 05.1 exists to avoid.
      requireSale: Boolean = true,
      minDiscountPct: Option[Int] = None,
      active: Boolean = true,
  ): Either[InvalidWatch, WatchItem] = {
    val cleaned  = terms.map(_.trim).filter(_.nonEmpty)
    val excluded = excludeTerms.map(_.trim).filter(_.nonEmpty)

    def normalized(t: String) = TextNormalizer.normalize(t).joined
    val selfDefeating         = cleaned.find(t => excluded.exists(e => normalized(e) == normalized(t)))

    for {
      nel <- NonEmptyList.fromList(cleaned).toRight(InvalidWatch.NoTerms)
      _   <- Either.cond(label.trim.nonEmpty, (), InvalidWatch.EmptyLabel)
      _   <- selfDefeating.toLeft(()).left.map(InvalidWatch.TermAlsoExcluded.apply)
      _ <- minDiscountPct match {
        case Some(p) if p < 1 || p > 100 => Left(InvalidWatch.BadDiscount(p))
        case _                           => Right(())
      }
    } yield WatchItem(id, label.trim, nel, excluded, merchants, maxPrice, requireSale, minDiscountPct, active)
  }
}
