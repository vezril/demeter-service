package demeter.alerting

import java.time.format.DateTimeFormatter
import java.time.{Instant, ZoneId}
import java.util.{Locale => JLocale}

import demeter.foundations.{Locale, Money}
import demeter.pricehistory.DealVerdict
import io.circe.Json
import io.circe.syntax._

/** Spec 05.3 — the alert payload and its renders.
  *
  * Rendering is separated from delivery (05.4/05.5) so wording is testable
  * without a network, and so the same alert can render differently for Home
  * Assistant vs a phone push. A no-price promo renders its sale text — never a
  * fabricated price.
  */
final case class Alert(
    watchLabel: String,
    merchantName: String,
    itemName: String,
    price: Option[Money],
    saleText: Option[String],
    verdict: DealVerdict,
    validTo: Instant,
    score: Double,
) {

  def renderPlain(locale: Locale, zone: ZoneId = Alert.MontrealZone): String = {
    val head = price match {
      case Some(p)   => s"${Alert.formatMoney(p, locale)} ${itemName}"
      case None      => saleText.map(t => s"$t — $itemName").getOrElse(itemName)
    }
    val ends = locale match {
      case Locale.FrCa => s"jusqu'au ${Alert.formatDate(validTo, locale, zone)}"
      case Locale.EnCa => s"ends ${Alert.formatDate(validTo, locale, zone)}"
    }
    s"$head @ $merchantName — ${verdict.phrase} ($ends)"
  }

  def renderStructured: Json =
    Json.obj(
      "watch"        -> watchLabel.asJson,
      "item"         -> itemName.asJson,
      "merchant"     -> merchantName.asJson,
      "price_cents"  -> price.map(_.cents).asJson,
      "sale_text"    -> saleText.asJson,
      "verdict"      -> verdict.phrase.asJson,
      "verdict_kind" -> verdict.getClass.getSimpleName.stripSuffix("$").asJson,
      "valid_to"     -> validTo.toString.asJson,
      "score"        -> score.asJson,
    )
}

object Alert {

  val MontrealZone: ZoneId = ZoneId.of("America/Montreal")

  def of(deal: Deal, merchantName: String, locale: Locale): Alert =
    Alert(
      watchLabel = deal.watch.label,
      merchantName = merchantName,
      itemName = deal.observation.name.primary(locale).getOrElse(deal.observation.rawName),
      price = deal.observation.effectivePrice,
      saleText = deal.observation.saleText,
      verdict = deal.verdict,
      validTo = deal.observation.validTo,
      score = deal.score,
    )

  /** French-Canadian convention: "2,50 $"; English: "$2.50". */
  def formatMoney(m: Money, locale: Locale): String =
    locale match {
      case Locale.FrCa => s"${m.format.replace('.', ',')} $$"
      case Locale.EnCa => s"$$${m.format}"
    }

  def formatDate(at: Instant, locale: Locale, zone: ZoneId): String = {
    val pattern = locale match {
      case Locale.FrCa => "d MMM"
      case Locale.EnCa => "MMM d"
    }
    val jLocale = locale match {
      case Locale.FrCa => JLocale.CANADA_FRENCH
      case Locale.EnCa => JLocale.CANADA
    }
    DateTimeFormatter.ofPattern(pattern, jLocale).withZone(zone).format(at)
  }
}
