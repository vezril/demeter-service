package demeter.normalization

import java.time.Instant

import demeter.foundations._
import org.scalatest.funsuite.AnyFunSuite

/** Spec 02.6 — assemble a PriceObservation from a FlyerItem: the precedence
  * ladder, confidence-minimum rule, and unit-price attachment. Tags: @pure.
  * The single most important mutation-testing target in the system.
  */
final class ObservationAssemblerSpec extends AnyFunSuite {

  private val from = Instant.parse("2026-07-23T04:00:00Z")
  private val to   = Instant.parse("2026-07-30T03:59:59Z")
  private val now  = Instant.parse("2026-07-26T12:00:00Z")

  private def item(
      rawName: String = "Butter",
      current: Option[Long] = None,
      original: Option[Long] = None,
      saleStory: Option[String] = None,
  ): FlyerItem =
    FlyerItem(
      sourceItemId = "1",
      flyerId = FlyerId(900L),
      merchantId = MerchantId(100),
      name = BilingualText.empty,
      rawName = rawName,
      currentPrice = current.map(Money.cents(_)),
      originalPrice = original.map(Money.cents(_)),
      saleStory = saleStory,
      validFrom = from,
      validTo = to,
    )

  private def assemble(i: FlyerItem) = ObservationAssembler.assemble(i, now, Locale.EnCa)

  test("a scalar price wins over a coexisting sale story") {
    val obs = assemble(item(current = Some(499L), saleStory = Some("2 for $5")))
    assert(obs.effectivePrice.map(_.cents) == Some(499L))
    assert(obs.priceBasis == PriceBasis.ScalarPrice)
    assert(obs.saleText.contains("2 for $5")) // still recorded
    assert(obs.confidence == Confidence.High)
  }

  test("a multibuy with no scalar price yields a derived unit price") {
    val obs = assemble(item(saleStory = Some("2 for $5")))
    assert(obs.effectivePrice.map(_.cents) == Some(250L))
    assert(obs.priceBasis == PriceBasis.MultiBuyUnit)
    assert(obs.confidence == Confidence.Medium)
  }

  test("percent-off with a known original price computes the sale price") {
    val obs = assemble(item(original = Some(2000L), saleStory = Some("25% off")))
    assert(obs.effectivePrice.map(_.cents) == Some(1500L))
    assert(obs.priceBasis == PriceBasis.ParsedFromText)
  }

  test("percent-off without a base price records a promo but no price") {
    val obs = assemble(item(saleStory = Some("50% off")))
    assert(obs.effectivePrice.isEmpty)
    assert(obs.priceBasis == PriceBasis.PercentOffUnknown)
    assert(obs.saleText.exists(_.contains("50%")))
  }

  test("a loyalty-points offer becomes an opaque promo observation") {
    val obs = assemble(item(saleStory = Some("25 points")))
    assert(obs.effectivePrice.isEmpty)
    assert(obs.priceBasis == PriceBasis.Unknown)
    assert(obs.saleText.contains("25 points"))
  }

  test("a price hiding in the name text is scraped at low confidence") {
    val obs = assemble(item(rawName = "Butter $4.99 special"))
    assert(obs.effectivePrice.map(_.cents) == Some(499L))
    assert(obs.priceBasis == PriceBasis.ParsedFromText)
    assert(obs.confidence == Confidence.Low)
  }

  test("an unreadable name does NOT drag down a clean price") {
    // "Cola 2L" is language-ambiguous, so the name split is Low. Measured on a
    // real flyer run this was true of ~65% of items — brand-heavy names the
    // language heuristic cannot classify. Collapsing the two confidences meant
    // every one of those clean scalar prices was weighted at 0.4 in the history
    // median (07.2), which is what decides whether you get alerted at all.
    val obs = assemble(item(rawName = "Cola 2L", current = Some(199L)))
    assert(obs.priceBasis == PriceBasis.ScalarPrice)
    assert(obs.priceConfidence == Confidence.High, "the price is a clean scalar and stays trusted")
    assert(obs.matchConfidence == Confidence.Low, "identity is genuinely uncertain")
    assert(obs.confidence == Confidence.Low, "the combined view is still available, and still the minimum")
  }

  test("a shaky price does NOT make identity look shaky either") {
    // the mirror case: a price scraped from free text, on a name that split cleanly
    val obs = assemble(item(rawName = "Beurre d'arachide croquant $4.99"))
    assert(obs.priceBasis == PriceBasis.ParsedFromText)
    assert(obs.priceConfidence == Confidence.Low)
    assert(obs.matchConfidence == Confidence.High, "the French name resolved unambiguously")
  }

  test("an ambiguous size lowers identity, not price") {
    // two size tokens means the product key may be wrong — an identity problem
    val obs = assemble(item(rawName = "Juice 1 L bottle, 500 g net", current = Some(499L)))
    assert(obs.priceConfidence == Confidence.High)
    assert(obs.matchConfidence != Confidence.High)
  }

  test("a parseable size attaches a unit price regardless of basis") {
    val obs = assemble(item(rawName = "Milk 4 L", current = Some(499L)))
    assert(obs.unitPrice.map(_.price.cents) == Some(125L))
    assert(obs.unitPrice.map(_.per) == Some(StdUnit.PerLitre))
  }

  test("raw upstream name is preserved verbatim through normalization") {
    val raw = "  Natrel 3.25%  |  LAIT 3.25%  "
    val obs = assemble(item(rawName = raw, current = Some(499L)))
    assert(obs.rawName == raw)
  }

  test("the observation carries a stable product key and the flyer window") {
    val obs1 = assemble(item(rawName = "Natrel Milk 4 L", current = Some(499L)))
    val obs2 = assemble(item(rawName = "NATREL MILK 4L", current = Some(459L)))
    assert(obs1.productKey == obs2.productKey)
    assert(obs1.validFrom == from)
    assert(obs1.validTo == to)
  }
  test("an item whose size normalizes to zero still assembles") {
    // This is the whole point of the fix. assemble() throwing did not lose one
    // ITEM -- the throw escaped to the flyer, and on 2026-08-26 three flyers
    // were lost entire, roughly 410 observations, one bad item each. The name
    // and the price are what make an observation worth storing; the size is
    // optional and already modelled as such.
    val obs = assemble(item(rawName = "MYSTERY SNACK 0 G", current = Some(499L)))
    assert(obs.effectivePrice.contains(Money.cents(499L)))
    assert(obs.size.isEmpty, "a zero size must not be recorded as a size")
    assert(obs.unitPrice.isEmpty, "no size means no unit price")
    assert(obs.rawName == "MYSTERY SNACK 0 G", "the raw name stays verbatim as the audit trail")
  }

}
