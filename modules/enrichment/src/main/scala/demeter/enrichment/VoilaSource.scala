package demeter.enrichment

import java.net.URLEncoder
import java.nio.charset.StandardCharsets

import cats.effect.kernel.Temporal
import cats.syntax.all._
import demeter.foundations._
import demeter.ingestion.{HttpPolicy, HttpResponse, HttpTransport}
import io.circe.Json

/** Spec 06.3, rebuilt against the endpoint as it actually behaves.
  *
  * VERIFIED LIVE 2026-08-20 (see specs/06-enrichment/06.0-endpoint-verification.md).
  * The original spec assumed `GET /api/v5/products/search` returning JSON, gated
  * on a session cookie. None of that held:
  *
  *   - `/products/search` 302s to `/search?q={term}`.
  *   - No session-establishment step is needed; a plain GET returns 200.
  *   - The priced product list is NOT served by any JSON endpoint. It is
  *     server-rendered, with the full product data embedded in the page as
  *     `window.__INITIAL_STATE__`.
  *
  * So this reads the embedded state rather than scraping the DOM: the payload is
  * the same structured data the page renders from, which makes it far less
  * brittle than CSS selectors, but it is still someone's internal shape and can
  * change without notice. The @contract test against the captured fixture is the
  * early-warning system.
  */
final case class VoilaConfig(
    baseUrl: String = "https://voila.ca",
    merchantId: MerchantId = MerchantId(4592), // IGA on the Flipp side
)

final class VoilaSource[F[_]](
    config: VoilaConfig,
    transport: HttpTransport[F],
    policy: HttpPolicy[F],
)(implicit F: Temporal[F])
    extends EnrichmentSource[F] {

  val name: SourceName                  = SourceName("voila")
  val merchantsCovered: Set[MerchantId] = Set(config.merchantId)

  def lookup(query: String, near: PostalCode, locale: Locale): F[Either[DealWatchError, List[EnrichedPrice]]] = {
    val url = VoilaSource.searchUrl(config.baseUrl, query)
    val headers = Map(
      "User-Agent"      -> policy.config.userAgent,
      "Accept"          -> "text/html,application/xhtml+xml",
      "Accept-Language" -> (if (locale == Locale.FrCa) "fr-CA,fr;q=0.9" else "en-CA,en;q=0.9"),
    )

    policy.run(url)(transport.get(url, headers).map(_.flatMap(classify(url, _)))).flatMap {
      case Left(e) => F.pure(Left(e))
      case Right(resp) =>
        F.realTimeInstant.map { now =>
          VoilaSource.decodePage(new String(resp.body, StandardCharsets.UTF_8), config.merchantId, name, now)
        }
    }
  }

  private def classify(url: String, resp: HttpResponse): Either[DealWatchError, HttpResponse] =
    if (resp.status / 100 == 2) Right(resp) else Left(DealWatchError.HttpStatus(resp.status, url))
}

object VoilaSource {

  /** `/products/search` redirects here; going straight to `/search` saves a hop. */
  def searchUrl(baseUrl: String, term: String): String =
    s"$baseUrl/search?q=${URLEncoder.encode(term, StandardCharsets.UTF_8.name).replace("+", "%20")}"

  // (?s) so a state blob containing newlines is still found — the captured page
  // happens to emit it on one line, but that is not something to depend on.
  private val StateMarker = """(?s)window\.__INITIAL_STATE__\s*=\s*(\{.*?\});?\s*</script>""".r

  /** Voilà labels the unit basis with an i18n key rather than a unit. Only the
    * bases we have actually seen are mapped; anything else yields no unit price
    * rather than a guessed one, because a wrong basis is worse than none — 07.3
    * would compare across incompatible units.
    */
  def unitBasisOf(label: String): Option[(StdUnit, BigDecimal)] =
    label.toLowerCase match {
      case l if l.endsWith("per.100ml") => Some((StdUnit.PerLitre, BigDecimal(10)))   // per 100 mL -> per L
      case l if l.endsWith("per.100g")  => Some((StdUnit.PerKg, BigDecimal(10)))      // per 100 g  -> per kg
      case l if l.endsWith("per.litre") || l.endsWith("per.l")  => Some((StdUnit.PerLitre, BigDecimal(1)))
      case l if l.endsWith("per.kg")    => Some((StdUnit.PerKg, BigDecimal(1)))
      case l if l.endsWith("per.each") || l.endsWith("per.item") => Some((StdUnit.PerItem, BigDecimal(1)))
      case _                            => None
    }

  def decodePage(
      html: String,
      merchant: MerchantId,
      source: SourceName,
      now: java.time.Instant,
  ): Either[DealWatchError, List[EnrichedPrice]] =
    StateMarker.findFirstMatchIn(html) match {
      case None =>
        Left(DealWatchError.Decode(source.value, "window.__INITIAL_STATE__", "embedded state not found in page"))
      case Some(m) =>
        io.circe.parser
          .parse(m.group(1))
          .leftMap(f => DealWatchError.Decode(source.value, "window.__INITIAL_STATE__", s"not JSON: ${f.message}"))
          .map(decodeState(_, merchant, source, now))
    }

  /** Individual unparseable products are dropped rather than failing the lookup —
    * enrichment is advisory (06.1) and one odd entity must not cost the rest.
    */
  def decodeState(state: Json, merchant: MerchantId, source: SourceName, now: java.time.Instant): List[EnrichedPrice] =
    state.hcursor
      .downField("data").downField("products").downField("productEntities")
      .focus
      .flatMap(_.asObject)
      .map(_.toList.flatMap { case (_, product) => decodeProduct(product, merchant, source, now) })
      .getOrElse(Nil)

  def decodeProduct(
      product: Json,
      merchant: MerchantId,
      source: SourceName,
      now: java.time.Instant,
  ): Option[EnrichedPrice] = {
    val c     = product.hcursor
    val price = c.downField("price")

    c.get[String]("name").toOption.map { productName =>
      val current  = money(price.downField("current"))
      val original = money(price.downField("original"))

      // `original` is present only while the item is on sale. With it, the sale
      // is current and the regular is original; without it, `current` IS the
      // regular shelf price — which is exactly the baseline 07.3 wants.
      val (regular, sale) = original match {
        case Some(_) => (original, current)
        case None    => (current, None)
      }

      val unitPrice = for {
        label       <- price.downField("unit").get[String]("label").toOption
        (unit, mul) <- unitBasisOf(label)
        amount      <- money(price.downField("unit").downField("current"))
      } yield UnitPrice(Money.cents((BigDecimal(amount.cents) * mul).toLong, amount.currency), unit)

      EnrichedPrice(
        merchantId = merchant,
        name = BilingualText.enOnly(productName),
        regularPrice = regular,
        salePrice = sale,
        unitPrice = unitPrice,
        source = source,
        // Voilà is an online storefront; its prices are a reference, not the
        // in-store flyer truth (06.3), so 07 weighs them as advisory.
        provenance = PriceProvenance.OnlineReference,
        fetchedAt = now,
      )
    }
  }

  /** Amounts arrive as decimal strings with an explicit currency: {"amount": "5.69", "currency": "CAD"}. */
  private def money(c: io.circe.ACursor): Option[Money] =
    for {
      amount <- c.get[String]("amount").toOption
      if c.get[String]("currency").toOption.forall(_ == "CAD")
      parsed <- Money.fromDecimal(amount).toOption
    } yield parsed
}
