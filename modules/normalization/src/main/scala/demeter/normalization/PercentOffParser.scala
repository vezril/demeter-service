package demeter.normalization

import demeter.foundations.{Locale, Money}

/** Spec 02.3 — "50% off", "jusqu'à 40%", "1/2 price", bare "gratuit"/"free".
  * When a base price is known the sale price is computed; when not, the rate is
  * still recorded so the item stays a flaggable promo (PriceBasis.PercentOffUnknown).
  * "buy 2 get 1 free" is 02.2's business, never a percent-off; "25 points"
  * falls through to nothing.
  */
final case class PercentOff(
    rate: Int,             // 0..100
    isUpperBound: Boolean, // "up to" / "jusqu'à"
    salePrice: Option[Money],
)

object PercentOffParser {

  private val PercentPattern   = """(\d{1,3})\s*%""".r
  private val HalfPricePattern = """(?i)\b(?:1/2\s+price|half\s+price|moiti[eé]\s+prix)\b""".r
  private val FreePattern      = """(?i)^\s*(?:free|gratuit[e]?s?)\s*[!.]?\s*$""".r
  private val UpperBound       = """(?i)\b(?:up\s+to|jusqu)""".r
  private val BogoGuard        = """(?i)\b(?:buy|achetez)\b.*\b(?:free|gratuits?)\b""".r

  def parsePercentOff(text: String, basePrice: Option[Money], locale: Locale): Option[PercentOff] =
    Option(text).map(_.trim).filter(_.nonEmpty).flatMap { t =>
      if (BogoGuard.findFirstIn(t).isDefined) None // free-item *bundles* belong to 02.2
      else
        rateOf(t).map { rate =>
          PercentOff(
            rate = rate,
            isUpperBound = UpperBound.findFirstIn(t).isDefined,
            salePrice = basePrice.map(b => Money.cents(b.cents * (100L - rate), b.currency).divideEvenly(100)),
          )
        }
    }

  private def rateOf(text: String): Option[Int] =
    PercentPattern.findFirstMatchIn(text).map(_.group(1).toInt).filter(r => r >= 1 && r <= 100) match {
      case some @ Some(_) => some
      case None =>
        if (HalfPricePattern.findFirstIn(text).isDefined) Some(50)
        else if (FreePattern.findFirstIn(text).isDefined) Some(100)
        else None
    }
}
