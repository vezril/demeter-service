package demeter.normalization

import java.time.Instant

import demeter.foundations._

/** Spec 02.6 — the orchestrating pure function of the normalization context:
  * one messy FlyerItem in, one storable PriceObservation out.
  *
  * The precedence ladder is the core pinned decision, kept as an ordered list
  * of strategies so re-ordering is a one-line change with an obvious test diff:
  *   1. scalar current_price            -> ScalarPrice, High
  *   2. multibuy with derivable unit    -> MultiBuyUnit, Medium
  *   3. percent-off with original price -> ParsedFromText, Medium
  *   4. percent-off without a base      -> price None, PercentOffUnknown
  *   5. bare price token in the name    -> ParsedFromText, Low
  *   6. none of the above               -> price None, Unknown (saleText kept)
  * Price confidence and match confidence are recorded SEPARATELY. They answer
  * different questions — "is this price real?" versus "did we identify the item
  * correctly?" — and collapsing them to a single minimum meant an unclassifiable
  * product name dragged down a perfectly clean scalar price, which then weighed
  * that price down in the history median (07.2). Measured against a real flyer
  * run, that mis-weighted about 65% of all priced observations.
  */
object ObservationAssembler {

  private final case class Derived(price: Option[Money], basis: PriceBasis, confidence: Confidence)

  private type Strategy = (FlyerItem, String, Locale) => Option[Derived]

  // Prices sometimes hide in the name text ("$4.99" mid-string); findable tokens only.
  private val NamePriceCandidate = """\$\s*\d[\d.,]*|\d+[.,]\d{2}\s*\$|\d+\s*¢""".r

  private val ladder: List[Strategy] = List(
    // 1. scalar — a coexisting sale story is recorded but never overrides
    (item, _, _) => item.currentPrice.map(p => Derived(Some(p), PriceBasis.ScalarPrice, Confidence.High)),
    // 2. multibuy with derivable unit price (BOGO uses the original price as its base)
    (item, sale, locale) =>
      MultiBuyParser
        .parseMultiBuy(sale, item.originalPrice, locale)
        .flatMap(_.unitPrice)
        .map(u => Derived(Some(u), PriceBasis.MultiBuyUnit, Confidence.Medium)),
    // 3. percent-off with a known base
    (item, sale, locale) =>
      item.originalPrice
        .flatMap(base => PercentOffParser.parsePercentOff(sale, Some(base), locale).flatMap(_.salePrice))
        .map(sp => Derived(Some(sp), PriceBasis.ParsedFromText, Confidence.Medium)),
    // 4. percent-off without a base — still a flaggable promo
    (_, sale, locale) =>
      PercentOffParser
        .parsePercentOff(sale, None, locale)
        .map(_ => Derived(None, PriceBasis.PercentOffUnknown, Confidence.Medium)),
    // 5. a bare price token findable in the raw name
    (item, _, locale) =>
      NamePriceCandidate
        .findFirstIn(item.rawName)
        .flatMap(PriceTextParser.parsePriceToken(_, locale))
        .map(t => Derived(Some(t.amount), PriceBasis.ParsedFromText, Confidence.Low)),
  )

  private val fallback = Derived(None, PriceBasis.Unknown, Confidence.High)

  def assemble(item: FlyerItem, observedAt: Instant, locale: Locale): PriceObservation = {
    val saleText = item.saleStory.getOrElse("")
    val split    = BilingualSplitter.splitBilingual(item.rawName)
    val sizeDet  = UnitPriceCalculator.parseSizeDetailed(item.rawName, locale)
    val size     = sizeDet.map(_.size)

    val derived = ladder.view.flatMap(s => s(item, saleText, locale)).headOption.getOrElse(fallback)

    val sizeConfidence = if (sizeDet.exists(_.ambiguous)) Confidence.Medium else Confidence.High
    val unitPrice      = for { p <- derived.price; s <- size } yield UnitPriceCalculator.unitPrice(p, s)

    PriceObservation(
      productKey = ProductKeys.productKey(item.merchantId, split.text, size),
      merchantId = item.merchantId,
      flyerId = item.flyerId,
      observedAt = observedAt,
      name = split.text,
      rawName = item.rawName, // verbatim — the audit trail (00.4)
      effectivePrice = derived.price,
      priceBasis = derived.basis,
      originalPrice = item.originalPrice,
      size = size,
      unitPrice = unitPrice,
      saleText = item.saleStory,
      validFrom = item.validFrom,
      validTo = item.validTo,
      priceConfidence = derived.confidence,
      // identity: how the name split went, tempered by an ambiguous size —
      // a wrong size means a wrong product key, which is an identity problem
      matchConfidence = split.confidence.min(sizeConfidence),
    )
  }
}
