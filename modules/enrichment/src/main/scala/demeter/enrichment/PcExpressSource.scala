package demeter.enrichment

import java.nio.charset.StandardCharsets

import cats.effect.kernel.Temporal
import cats.syntax.all._
import demeter.foundations._
import demeter.ingestion.{BotWallDetection, HttpPolicy, HttpResponse}
import io.circe.Json

/** Spec 06.2 — Loblaw's PC Express backend (Maxi, Provigo, No Frills, …).
  *
  * The highest-value enrichment: it exposes regular AND unit prices Flipp omits.
  * The `X-Apikey` is a static, app-embedded key (not a user credential) supplied
  * by the operator through config — never a source literal (08.4).
  *
  * STATUS: endpoint shape from public reverse-engineering, NOT executed live.
  * The @contract test is what confirms or denies it at build time.
  */
final case class PcExpressConfig(
    apiKey: String,
    baseUrl: String = "https://api.pcexpress.ca/product-facade/v3/products/search",
    banners: Map[MerchantId, String] = Map.empty,
    pageSize: Int = 20,
)

/** POST-capable transport; separate from the GET-only ingestion transport. */
trait PostTransport[F[_]] {
  def post(url: String, headers: Map[String, String], body: String): F[Either[DealWatchError, HttpResponse]]
}

final class PcExpressSource[F[_]](
    config: PcExpressConfig,
    transport: PostTransport[F],
    policy: HttpPolicy[F],
)(implicit F: Temporal[F])
    extends EnrichmentSource[F] {

  val name: SourceName                   = SourceName("pcexpress")
  val merchantsCovered: Set[MerchantId]  = config.banners.keySet

  def lookup(query: String, near: PostalCode, locale: Locale): F[Either[DealWatchError, List[EnrichedPrice]]] =
    merchantsCovered.toList match {
      case Nil => F.pure(Right(Nil))
      case merchants =>
        merchants
          .traverse(m => lookupBanner(query, near, locale, m))
          .map(results => results.collectFirst { case Left(e) => e }.toLeft(results.collect { case Right(ps) => ps }.flatten))
    }

  def lookupBanner(
      query: String,
      near: PostalCode,
      locale: Locale,
      merchant: MerchantId,
  ): F[Either[DealWatchError, List[EnrichedPrice]]] = {
    val banner = config.banners.getOrElse(merchant, "")
    val body   = PcExpressSource.requestBody(query, banner, locale, config.pageSize)

    policy
      .run(config.baseUrl)(
        transport.post(config.baseUrl, headers(banner, locale), body).map(_.flatMap(classify))
      )
      .flatMap {
        case Left(e) => F.pure(Left(e))
        case Right(resp) =>
          F.realTimeInstant.map { now =>
            io.circe.parser
              .parse(new String(resp.body, StandardCharsets.UTF_8))
              .leftMap(f => DealWatchError.Decode(name.value, "", s"not JSON: ${f.message}"): DealWatchError)
              .map(PcExpressSource.decode(_, merchant, name, now))
          }
      }
  }

  /** The static key is injected from config; the banner header follows the target merchant. */
  def headers(banner: String, locale: Locale): Map[String, String] =
    Map(
      "Site-Banner"     -> banner,
      "X-Apikey"        -> config.apiKey,
      "Content-Type"    -> "application/json",
      "Accept-Language" -> (if (locale == Locale.FrCa) "fr-CA" else "en-CA"),
      "User-Agent"      -> policy.config.userAgent,
    )

  private def classify(resp: HttpResponse): Either[DealWatchError, HttpResponse] = {
    val text = new String(resp.body, StandardCharsets.UTF_8)
    BotWallDetection.classify(resp.status, text, config.baseUrl, policy.config.botWallSignatures) match {
      case Some(botWall)                  => Left(botWall)
      case None if resp.status / 100 == 2 => Right(resp)
      case None                           => Left(DealWatchError.HttpStatus(resp.status, config.baseUrl))
    }
  }
}

object PcExpressSource {

  def requestBody(term: String, banner: String, locale: Locale, pageSize: Int): String =
    Json
      .obj(
        "pagination" -> Json.obj("from" -> Json.fromInt(1), "size" -> Json.fromInt(pageSize)),
        "banner"     -> Json.fromString(banner),
        "lang"       -> Json.fromString(if (locale == Locale.FrCa) "fr" else "en"),
        "term"       -> Json.fromString(term),
      )
      .noSpaces

  /** Maps the product-facade response onto EnrichedPrice. Prices arrive as
    * strings like "$4.99" in this API, so a currency prefix is tolerated here
    * (unlike the Flipp price fields, 01.5).
    */
  def decode(json: Json, merchant: MerchantId, source: SourceName, now: java.time.Instant): List[EnrichedPrice] = {
    val products = json.hcursor.downField("results").values.orElse(json.hcursor.downField("products").values)
    products.toList.flatMap(_.toList).flatMap { p =>
      val c    = p.hcursor
      val name = c.downField("name").as[String].toOption.orElse(c.downField("title").as[String].toOption)
      name.map { n =>
        EnrichedPrice(
          merchantId = merchant,
          name = BilingualText.enOnly(n),
          regularPrice = money(c.downField("prices").downField("price").downField("value").focus)
            .orElse(money(c.downField("price").focus)),
          salePrice = money(c.downField("prices").downField("wasPrice").downField("value").focus)
            .orElse(money(c.downField("dealPrice").focus)),
          unitPrice = None,
          source = source,
          provenance = PriceProvenance.Shelf,
          fetchedAt = now,
        )
      }
    }
  }

  private def money(j: Option[Json]): Option[Money] =
    j.flatMap { v =>
      v.asNumber
        .flatMap(_.toBigDecimal)
        .flatMap(Money.fromBigDecimal(_).toOption)
        .orElse(v.asString.map(_.replace("$", "").trim).flatMap(Money.fromDecimal(_).toOption))
    }
}
