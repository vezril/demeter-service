package demeter.persistence

import demeter.foundations._
import doobie.Meta

/** Column codecs for the closed domain enums. Text columns on purpose —
  * readable in psql, additive when a case is added.
  */
object Codecs {

  private def enumMeta[A](name: String, to: A => String, all: List[A]): Meta[A] = {
    val byName = all.map(a => to(a) -> a).toMap
    Meta[String].timap(s => byName.getOrElse(s, sys.error(s"unknown $name in db: $s")))(to)
  }

  implicit val priceBasisMeta: Meta[PriceBasis] = enumMeta[PriceBasis](
    "price_basis",
    _.toString,
    List(PriceBasis.ScalarPrice, PriceBasis.MultiBuyUnit, PriceBasis.PercentOffUnknown, PriceBasis.ParsedFromText, PriceBasis.Unknown),
  )

  implicit val confidenceMeta: Meta[Confidence] =
    enumMeta[Confidence]("confidence", _.toString, List(Confidence.High, Confidence.Medium, Confidence.Low))

  implicit val stdUnitMeta: Meta[StdUnit] =
    enumMeta[StdUnit]("unit_basis", _.toString, List(StdUnit.PerLitre, StdUnit.PerKg, StdUnit.PerItem))

  implicit val localeMeta: Meta[Locale] =
    enumMeta[Locale]("locale", _.queryValue, List(Locale.FrCa, Locale.EnCa))
}
