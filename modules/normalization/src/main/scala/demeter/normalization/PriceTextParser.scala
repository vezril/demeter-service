package demeter.normalization

import demeter.foundations.{Locale, Money}

/** Spec 02.1 — pull a clean Money out of one free-text price token.
  *
  * The lowest layer of the normalization stack: 02.2/02.3 parse structure and
  * call down into this for the money tokens they find. Total (Option), pure.
  * A token only counts as a price when it carries a currency signal ($, ¢,
  * cents) or a decimal fraction — a bare "4" in "Milk 4 L" is a size, never
  * four cents.
  */
sealed abstract class PriceUnit extends Product with Serializable

object PriceUnit {
  case object PerLb   extends PriceUnit
  case object PerKg   extends PriceUnit
  case object Each    extends PriceUnit
  case object Per100g extends PriceUnit
}

final case class PriceToken(amount: Money, unit: Option[PriceUnit])

object PriceTextParser {

  private val UnitPatterns: List[(String, PriceUnit)] = List(
    "/\\s*lb\\b"       -> PriceUnit.PerLb,
    "\\bper\\s+lb\\b"  -> PriceUnit.PerLb,
    "/\\s*livre\\b"    -> PriceUnit.PerLb,
    "/\\s*100\\s*g\\b" -> PriceUnit.Per100g,
    "/\\s*kg\\b"       -> PriceUnit.PerKg,
    "\\bper\\s+kg\\b"  -> PriceUnit.PerKg,
    "\\bea\\.?\\b"     -> PriceUnit.Each,
    "\\beach\\b"       -> PriceUnit.Each,
    "\\bchacun[e]?\\b" -> PriceUnit.Each,
  )

  private val CentsPattern  = """(?i)^\s*(\d+)\s*(?:¢|cents?)\s*$""".r
  private val DollarPattern = """^\s*(\$)?\s*([\d.,   ]*\d|\.\d{1,2})\s*(\$)?\s*$""".r

  def parsePriceToken(text: String, locale: Locale): Option[PriceToken] = {
    val (unit, remainder) = extractUnit(text)
    val trimmed           = remainder.trim
    if (trimmed.isEmpty || !trimmed.exists(_.isDigit)) None
    else parseCents(trimmed).orElse(parseDollars(trimmed)).map(PriceToken(_, unit))
  }

  private def extractUnit(text: String): (Option[PriceUnit], String) =
    UnitPatterns
      .collectFirst {
        case (pattern, unit) if s"(?i)$pattern".r.findFirstIn(text).isDefined =>
          (Some(unit), text.replaceAll(s"(?i)$pattern", " "))
      }
      .getOrElse((None, text))

  private def parseCents(text: String): Option[Money] =
    text match {
      case CentsPattern(digits) => Some(Money.cents(digits.toLong))
      case _                    => None
    }

  private def parseDollars(text: String): Option[Money] =
    text match {
      case DollarPattern(pre, number, post) =>
        val hadDollar = pre != null || post != null
        canonicalDecimal(number.replaceAll("[   ]", "")).flatMap { canonical =>
          val hasFraction = canonical.contains('.')
          if (hadDollar || hasFraction) Money.fromDecimal(canonical).toOption else None
        }
      case _ => None
    }

  /** Resolve grouping vs decimal separators to a canonical dot-decimal string.
    * Grouping is stripped BEFORE the decimal mark is interpreted (the bug-prone
    * ordering): "1 234,05" -> "1234.05", "1,234.05" -> "1234.05", "4,99" -> "4.99".
    */
  private def canonicalDecimal(s: String): Option[String] = {
    val hasDot   = s.contains('.')
    val hasComma = s.contains(',')
    val resolved =
      if (hasDot && hasComma) {
        if (s.lastIndexOf('.') > s.lastIndexOf(',')) Some(s.replace(",", ""))
        else Some(s.replace(".", "").replace(',', '.'))
      } else if (hasComma) {
        val idx   = s.lastIndexOf(',')
        val after = s.length - idx - 1
        if (after == 2 && s.indexOf(',') == idx) Some(s.replace(',', '.')) // French decimal: 4,99
        else if (after == 3) Some(s.replace(",", ""))                      // grouping: 1,234
        else None
      } else Some(s)
    resolved.map(r => if (r.startsWith(".")) "0" + r else r)
  }
}
