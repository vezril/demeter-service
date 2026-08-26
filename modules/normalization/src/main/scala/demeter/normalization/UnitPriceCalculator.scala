package demeter.normalization

import scala.math.BigDecimal.RoundingMode

import demeter.foundations.{Locale, Money, Size, StdUnit, UnitPrice}

/** Spec 02.4 — extract a size from an item name and normalize prices to a
  * comparable basis (per litre / per kg / per item). `parseSize` and
  * `unitPrice` are split so size extraction is testable without prices and
  * reusable by 07. Returns None when no size is parseable — common, not an error.
  */
object UnitPriceCalculator {

  final case class SizeParse(size: Size, ambiguous: Boolean)

  private val Multipack =
    """(?i)(\d+)\s*[x×]\s*(\d+(?:[.,]\d+)?)\s*(ml|cl|l|litres?|liters?|g|kg)\b""".r
  private val Volume =
    """(?i)(\d+(?:[.,]\d+)?)\s*(ml|cl|l|litres?|liters?)\b""".r
  private val Weight =
    """(?i)(\d+(?:[.,]\d+)?)\s*(g|kg|grammes?|grams?)\b""".r
  private val Count =
    """(?i)(?:paquet\s+de\s+(\d+)|pack\s+of\s+(\d+)|bo[iî]te\s+de\s+(\d+)|(\d+)\s*[-\s]pack\b)""".r
  private val Dozen = """(?i)\b(?:dozen|douzaine)\b""".r

  def parseSize(name: String, locale: Locale): Option[Size] =
    parseSizeDetailed(name, locale).map(_.size)

  /** Detailed variant for 02.6: `ambiguous` is true when the name carries more
    * than one size-like token (v1 takes the first, confidence drops).
    */
  def parseSizeDetailed(name: String, locale: Locale): Option[SizeParse] = {
    val candidates = sizeCandidates(name)
    candidates.headOption.map { case (_, size) => SizeParse(size, ambiguous = candidates.size > 1) }
  }

  /** All size tokens found, ordered by position; the first wins. */
  private def sizeCandidates(name: String): List[(Int, Size)] = {
    // `.toInt` throws on anything that does not fit, and flyer text is not
    // obliged to be sensible -- "99999999999 x 500 ml" threw NumberFormatException
    // out of a pure parser. Same shape as the zero size: an unusable number is
    // not a size, so the candidate is skipped rather than raised.
    val multipacks = Multipack.findAllMatchIn(name).toList.flatMap { m =>
      m.group(1).toIntOption.map { pack =>
        val each  = decimal(m.group(2))
        val total = each * pack
        m.start -> toSize(total, m.group(3), pack)
      }
    }
    // exclude volume/weight matches that are part of a multipack expression
    def free(m: scala.util.matching.Regex.Match): Boolean =
      !Multipack.findAllMatchIn(name).exists(mp => m.start >= mp.start && m.start < mp.end)

    val volumes =
      Volume.findAllMatchIn(name).toList.filter(free).map(m => m.start -> toSize(decimal(m.group(1)), m.group(2), 1))
    val weights =
      Weight.findAllMatchIn(name).toList.filter(free).map(m => m.start -> toSize(decimal(m.group(1)), m.group(2), 1))
    val counts = Count.findAllMatchIn(name).toList.flatMap { m =>
      List(1, 2, 3, 4).flatMap(i => Option(m.group(i))).head.toIntOption.map { n =>
        m.start -> Size(BigDecimal(n), StdUnit.PerItem, n)
      }
    }
    val dozens = Dozen.findAllMatchIn(name).toList.map(m => m.start -> Size(BigDecimal(12), StdUnit.PerItem, 12))

    // Drop sizes that normalize to zero before they leave here.
    //
    // `round3` takes ml and g down by 1000, so anything under half a gram or
    // half a millilitre -- and any literal 0 in the flyer text -- lands on
    // 0.000. That reached `unitPrice`, whose `require` threw, and the throw was
    // caught at the FLYER level: on 2026-08-26 three of eighteen selected
    // flyers were lost whole, about 410 observations, to one bad item each.
    // Nothing in the run report showed it -- items.dropped was 0,
    // decodeFailureRate 0.0, partial false -- because the items never got as
    // far as being parsed, let alone dropped.
    //
    // A zero size is not a size. Discarding the candidate leaves `size` and
    // `unitPrice` as None, which the observation already models as ordinary
    // ("Returns None when no size is parseable -- common, not an error"), and
    // the item keeps the name and price that make it worth storing.
    (multipacks ++ volumes ++ weights ++ counts ++ dozens).filter(_._2.quantity > 0).sortBy(_._1)
  }

  private def decimal(s: String): BigDecimal = BigDecimal(s.replace(',', '.'))

  private def toSize(quantity: BigDecimal, unit: String, pack: Int): Size =
    unit.toLowerCase match {
      case "ml"                                          => Size(round3(quantity / 1000), StdUnit.PerLitre, pack)
      case "cl"                                          => Size(round3(quantity / 100), StdUnit.PerLitre, pack)
      case "l" | "litre" | "litres" | "liter" | "liters" => Size(round3(quantity), StdUnit.PerLitre, pack)
      case "g" | "gramme" | "grammes" | "gram" | "grams" => Size(round3(quantity / 1000), StdUnit.PerKg, pack)
      case "kg"                                          => Size(round3(quantity), StdUnit.PerKg, pack)
      case other                                         => sys.error(s"unreachable size unit: $other")
    }

  private def round3(d: BigDecimal): BigDecimal = d.setScale(3, RoundingMode.HALF_EVEN)

  /** Price per standard unit, half-even (00.1). $4.99 for 4 L -> 125 cents/L. */
  def unitPrice(price: Money, size: Size): UnitPrice = {
    require(size.quantity > 0, s"size quantity must be positive: ${size.quantity}")
    val cents = (BigDecimal(price.cents) / size.quantity).setScale(0, RoundingMode.HALF_EVEN).toLongExact
    UnitPrice(Money.cents(cents, price.currency), size.unit)
  }
}
