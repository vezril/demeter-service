package demeter.enrichment

import java.nio.charset.StandardCharsets
import java.time.Instant

import scala.concurrent.duration._

import cats.effect.kernel.{Ref, Temporal}
import cats.effect.std.Semaphore
import cats.syntax.all._
import demeter.foundations._
import demeter.ingestion.{HttpPolicy, HttpResponse, HttpTransport}
import io.circe.Json

/** Spec 06.4 — Canadian Tire's internal API (the one CanadianTracker uses).
  *
  * CT flyer data from Flipp is the worst-priced (mostly null prices, free
  * text), and CT inflates its struck-through "regular" — so its real per-SKU
  * regular price is exactly what discount depth needs. This source is slower
  * and more rate-sensitive than the grocery ones, so: lookups run SERIALLY
  * (never fanned out), under a stricter limit, and results are cached for the
  * whole flyer validity window.
  *
  * STATUS: shape known from the open CanadianTracker project, NOT executed live.
  */
final case class CanadianTireConfig(
    baseUrl: String = "https://apim.canadiantire.ca/v1/product/api/v1/product/sku/PriceAvailability",
    merchantId: MerchantId = MerchantId(2296),
    cacheFor: FiniteDuration = 7.days,
)

final case class CachedEnrichment(prices: List[EnrichedPrice], until: Instant)

final class CanadianTireSource[F[_]](
    config: CanadianTireConfig,
    transport: HttpTransport[F],
    policy: HttpPolicy[F],
    gate: Semaphore[F],                       // serializes: never fan CT out in parallel
    cache: Ref[F, Map[String, CachedEnrichment]],
)(implicit F: Temporal[F])
    extends EnrichmentSource[F] {

  val name: SourceName                  = SourceName("canadiantire")
  val merchantsCovered: Set[MerchantId] = Set(config.merchantId)

  def lookup(query: String, near: PostalCode, locale: Locale): F[Either[DealWatchError, List[EnrichedPrice]]] =
    lookupCachedUntil(query, near, locale, cacheUntil = None)

  /** @param cacheUntil when the caller knows the flyer's validity window, cache
    *   the result for exactly that window (06.4); otherwise fall back to config.
    */
  def lookupCachedUntil(
      query: String,
      near: PostalCode,
      locale: Locale,
      cacheUntil: Option[Instant],
  ): F[Either[DealWatchError, List[EnrichedPrice]]] =
    F.realTimeInstant.flatMap { now =>
      cache.get.map(_.get(cacheKey(query, near))).flatMap {
        case Some(hit) if hit.until.isAfter(now) => F.pure(Right(hit.prices))
        case _ =>
          gate.permit.use(_ => fetch(query, near, locale)).flatTap {
            case Right(prices) =>
              val until = cacheUntil.getOrElse(now.plusMillis(config.cacheFor.toMillis))
              cache.update(_.updated(cacheKey(query, near), CachedEnrichment(prices, until)))
            case Left(_) => F.unit
          }
      }
    }

  private def cacheKey(query: String, near: PostalCode): String = s"${near.canonical}:${query.toLowerCase.trim}"

  private def fetch(query: String, near: PostalCode, locale: Locale): F[Either[DealWatchError, List[EnrichedPrice]]] = {
    val url = s"${config.baseUrl}?lang=${if (locale == Locale.FrCa) "fr_CA" else "en_CA"}&q=$query"
    policy.run(url)(transport.get(url, Map("User-Agent" -> policy.config.userAgent)).map(_.flatMap(classify(url, _)))).flatMap {
      case Left(e) => F.pure(Left(e))
      case Right(resp) =>
        F.realTimeInstant.map { now =>
          io.circe.parser
            .parse(new String(resp.body, StandardCharsets.UTF_8))
            .leftMap(f => DealWatchError.Decode(name.value, "", s"not JSON: ${f.message}"): DealWatchError)
            .map(CanadianTireSource.decode(_, config.merchantId, name, now))
        }
    }
  }

  private def classify(url: String, resp: HttpResponse): Either[DealWatchError, HttpResponse] =
    if (resp.status / 100 == 2) Right(resp) else Left(DealWatchError.HttpStatus(resp.status, url))
}

object CanadianTireSource {

  /** CT gets a stricter rate limit than the grocery sources (06.4). */
  val StricterRateLimit: Int              = 1
  val StricterRateWindow: FiniteDuration  = 20.seconds

  def create[F[_]: Temporal](
      config: CanadianTireConfig,
      transport: HttpTransport[F],
      policy: HttpPolicy[F],
  ): F[CanadianTireSource[F]] =
    for {
      gate  <- Semaphore[F](1) // serial by construction
      cache <- Ref.of[F, Map[String, CachedEnrichment]](Map.empty)
    } yield new CanadianTireSource(config, transport, policy, gate, cache)

  def decode(json: Json, merchant: MerchantId, source: SourceName, now: Instant): List[EnrichedPrice] =
    json.hcursor.downField("skus").values
      .orElse(json.hcursor.downField("products").values)
      .toList
      .flatMap(_.toList)
      .flatMap { p =>
        val c = p.hcursor
        val label = c.get[String]("title").toOption
          .orElse(c.get[String]("name").toOption)
          .orElse(c.get[String]("code").toOption)
        label.map { n =>
          EnrichedPrice(
            merchantId = merchant,
            name = BilingualText.enOnly(n),
            regularPrice = money(c.downField("currentPrice").downField("value").focus)
              .orElse(money(c.downField("regularPrice").focus)),
            salePrice = money(c.downField("salePrice").downField("value").focus)
              .orElse(money(c.downField("salePrice").focus)),
            unitPrice = None,
            source = source,
            provenance = PriceProvenance.Shelf,
            fetchedAt = now,
          )
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
