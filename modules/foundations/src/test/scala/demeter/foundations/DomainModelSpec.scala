package demeter.foundations

import java.time.Instant
import org.scalatest.funsuite.AnyFunSuite

/** Spec 00.4 — domain model invariants. Tags: @pure.
  *
  * The normalization-behaviour scenarios of 00.4 (scalar price -> ScalarPrice/High,
  * rawName preserved through assembly) are exercised end-to-end in 02.6's suite;
  * here we pin representability and the window invariant.
  */
final class DomainModelSpec extends AnyFunSuite {

  private val from = Instant.parse("2026-07-23T00:00:00Z")
  private val to   = Instant.parse("2026-07-30T00:00:00Z")
  private val pc   = PostalCode.parse("H2X1Y6").toOption.get

  test("an item with no derivable price is representable") {
    val obs = PriceObservation(
      productKey = ProductKey("v1:test"),
      merchantId = MerchantId(1),
      flyerId = FlyerId(900L),
      observedAt = from,
      name = BilingualText.enOnly("Widget"),
      rawName = "Widget",
      effectivePrice = None,
      priceBasis = PriceBasis.PercentOffUnknown,
      originalPrice = None,
      size = None,
      unitPrice = None,
      saleText = Some("50% off"),
      validFrom = from,
      validTo = to,
      priceConfidence = Confidence.Medium,
      matchConfidence = Confidence.Medium,
    )
    assert(obs.effectivePrice.isEmpty)
    assert(obs.priceBasis == PriceBasis.PercentOffUnknown)
  }

  test("a flyer with a non-positive validity window is not a valid domain object") {
    for ((f, t) <- Seq((to, from), (from, from))) {
      val result = Flyer.of(FlyerId(7), MerchantId(1), "Weekly", f, t, pc, Locale.EnCa)
      assert(result.isLeft, s"window $f..$t should be rejected")
      // the rejection has to say WHICH flyer and WHY — 08.3 surfaces this verbatim
      val Left(err) = result
      assert(err.what == "Flyer(7)")
      assert(err.reason.contains("validity window"))
      assert(err.reason.contains(f.toString))
    }
  }

  test("a flyer with a positive validity window builds") {
    assert(Flyer.of(FlyerId(1), MerchantId(1), "Weekly", from, to, pc, Locale.EnCa).isRight)
  }

  test("confidence min is the lattice meet") {
    import Confidence._
    assert(High.min(Low) == Low)
    assert(Low.min(High) == Low)
    assert(High.min(Medium) == Medium)
    assert(Medium.min(Medium) == Medium)
  }
}
