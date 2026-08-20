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

  test("confidence is the minimum of price and name-split confidence") {
    // "Cola 2L" is language-ambiguous -> name split Low; scalar price is High
    val obs = assemble(item(rawName = "Cola 2L", current = Some(199L)))
    assert(obs.priceBasis == PriceBasis.ScalarPrice)
    assert(obs.confidence == Confidence.Low)
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
}
