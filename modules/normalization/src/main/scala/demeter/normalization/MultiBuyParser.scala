package demeter.normalization

import demeter.foundations.{Locale, Money}

/** Spec 02.2 — "2 for $5" / "3/$5.00" / "2 pour 5 $" / "buy 2 get 1 free" to a
  * usable per-unit Money. Division is half-even via 00.1. Returns None when the
  * text has no multibuy structure at all so 02.6 can fall through.
  */
final case class MultiBuy(
    quantity: Int,
    bundlePrice: Option[Money], // "$5" in "2 for $5"; None for pure BOGO
    freeQuantity: Int,          // M in buy-N-get-M-free; 0 otherwise
    unitPrice: Option[Money],   // derived; None when underivable
)

object MultiBuyParser {

  private val Bogo =
    """(?i)\b(?:buy|achetez)\s+(\d+)\s*,?\s*(?:get|obtenez)\s+(\d+)\s+(?:free|gratuits?)\b""".r

  // "2 for $5", "3/$5.00", "2 pour 5 $" — the money segment ends on a digit or $
  // so a ", save $1.98" marketing tail is never swallowed into the bundle price.
  private val NForX =
    """(?i)\b(\d+)\s*(?:for|pour|/)\s*((?:\$\s*)?\d(?:[\d., ]*\d)?(?:\s*\$)?)""".r

  def parseMultiBuy(text: String, basePrice: Option[Money], locale: Locale): Option[MultiBuy] =
    Option(text).map(_.trim).filter(_.nonEmpty).flatMap { t =>
      bogo(t, basePrice).orElse(nForX(t, locale))
    }

  private def bogo(text: String, basePrice: Option[Money]): Option[MultiBuy] =
    Bogo.findFirstMatchIn(text).flatMap { m =>
      val paid = m.group(1).toInt
      val free = m.group(2).toInt
      if (paid < 1 || free < 1) None
      else {
        // paid * base spread across the paid+free items you leave with
        val unit = basePrice.map(b => Money.cents(b.cents * paid, b.currency).divideEvenly(paid + free))
        Some(MultiBuy(quantity = paid, bundlePrice = None, freeQuantity = free, unitPrice = unit))
      }
    }

  private def nForX(text: String, locale: Locale): Option[MultiBuy] =
    NForX.findFirstMatchIn(text).flatMap { m =>
      val qty = m.group(1).toInt
      if (qty < 2) None // "1/2 price" is a percent-off (02.3), not a multibuy
      else
        PriceTextParser.parsePriceToken(m.group(2), locale).map { token =>
          MultiBuy(
            quantity = qty,
            bundlePrice = Some(token.amount),
            freeQuantity = 0,
            unitPrice = Some(token.amount.divideEvenly(qty)),
          )
        }
    }
}
