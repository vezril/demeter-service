package demeter.alerting

import demeter.foundations.{Locale, Money}
import demeter.pricehistory.DealVerdict
import org.scalatest.funsuite.AnyFunSuite
import AlertingFixtures._

/** Spec 05.3 — render alerts. Tags: @pure. */
final class AlertRenderSpec extends AnyFunSuite {

  private def alert(
      cents: Option[Long] = Some(250L),
      saleText: Option[String] = None,
      verdict: DealVerdict = DealVerdict.BestEver(8),
      locale: Locale = Locale.EnCa,
  ): Alert =
    Alert.of(deal(o = obs(cents, saleText = saleText), verdict = verdict), merchantName = "Metro", locale = locale)

  test("a priced deal renders price, merchant, and verdict") {
    val text = alert().renderPlain(Locale.EnCa)
    assert(text.contains("2.50"))
    assert(text.contains("Metro"))
    assert(text.contains("cheapest in 8 weeks"))
  }

  test("a no-price promo renders the sale text and never a fabricated price") {
    val text = alert(cents = None, saleText = Some("50% off"), verdict = DealVerdict.Unknown).renderPlain(Locale.EnCa)
    assert(text.contains("50% off"))
    assert(!text.matches(""".*\$\d.*"""))
  }

  test("French locale renders French conventions") {
    val text = alert(locale = Locale.FrCa).renderPlain(Locale.FrCa)
    assert(text.contains("2,50 $"), s"got: $text")
    assert(text.contains("jusqu'au"), s"got: $text")
  }

  test("English locale puts the dollar sign in front") {
    assert(Alert.formatMoney(Money.cents(250), Locale.EnCa) == "$2.50")
    assert(Alert.formatMoney(Money.cents(250), Locale.FrCa) == "2,50 $")
  }

  test("the item name uses the preferred locale form") {
    assert(alert(locale = Locale.FrCa).itemName == "Lait Natrel 4 L")
    assert(alert(locale = Locale.EnCa).itemName == "Natrel Milk 4 L")
  }

  test("the structured render carries machine-readable fields for Home Assistant") {
    val json = alert().renderStructured
    val c    = json.hcursor
    assert(c.get[String]("item").toOption.contains("Natrel Milk 4 L"))
    assert(c.get[String]("merchant").toOption.contains("Metro"))
    assert(c.get[Long]("price_cents").toOption.contains(250L))
    assert(c.get[String]("verdict").toOption.contains("cheapest in 8 weeks"))
    assert(c.get[String]("valid_to").toOption.exists(_.startsWith("2026-07-30")))
  }

  test("the structured render carries a null price for a no-price promo") {
    val json = alert(cents = None, saleText = Some("50% off")).renderStructured
    assert(json.hcursor.get[Option[Long]]("price_cents").toOption.flatten.isEmpty)
    assert(json.hcursor.get[String]("sale_text").toOption.contains("50% off"))
  }
}
