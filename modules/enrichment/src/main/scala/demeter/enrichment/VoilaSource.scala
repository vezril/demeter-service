package demeter.enrichment

import java.net.URLEncoder
import java.nio.charset.StandardCharsets

import cats.effect.kernel.{Ref, Temporal}
import cats.syntax.all._
import demeter.foundations._
import demeter.ingestion.{HttpPolicy, HttpResponse, HttpTransport}
import io.circe.Json

/** Spec 06.3 — Voilà (Sobeys/IGA), the best structured source for IGA prices.
  *
  * The fragile part is the session cookie, modelled as an explicit
  * "establish session" step the adapter can redo: one 401 re-establishes and
  * retries once; a second 401 degrades the source. Voilà prices are marked
  * OnlineReference — they can differ from the in-store flyer price, so 07 treats
  * them as advisory rather than flyer truth.
  *
  * STATUS: endpoint shape from public reverse-engineering, NOT executed live.
  */
final case class VoilaConfig(
    baseUrl: String = "https://voila.ca/api/v5/products/search",
    sessionUrl: String = "https://voila.ca/api/v5/sessions",
    merchantId: MerchantId = MerchantId(4592), // IGA on the Flipp side
    limit: Int = 20,
)

final class VoilaSource[F[_]](
    config: VoilaConfig,
    transport: HttpTransport[F],
    policy: HttpPolicy[F],
    session: Ref[F, Option[String]],
)(implicit F: Temporal[F])
    extends EnrichmentSource[F] {

  val name: SourceName                  = SourceName("voila")
  val merchantsCovered: Set[MerchantId] = Set(config.merchantId)

  def lookup(query: String, near: PostalCode, locale: Locale): F[Either[DealWatchError, List[EnrichedPrice]]] =
    ensureSession.flatMap {
      case Left(e) => F.pure(Left(e))
      case Right(cookie) =>
        search(query, locale, cookie).flatMap {
          // an expired session: re-establish once, then retry; a second 401 degrades
          case Left(DealWatchError.HttpStatus(401, _)) =>
            session.set(None) *> ensureSession.flatMap {
              case Left(e)      => F.pure(Left(e): Either[DealWatchError, List[EnrichedPrice]])
              case Right(fresh) => search(query, locale, fresh)
            }
          case other => F.pure(other)
        }
    }

  /** Establishes the session cookie if we don't hold one.
    *
    * A transport failure here propagates as itself (a 503 stays a retriable
    * 503) so the orchestrator can distinguish "Voilà is briefly down" from
    * "our session was rejected" and degrade only the latter (08.2).
    */
  def ensureSession: F[Either[DealWatchError, String]] =
    session.get.flatMap {
      case Some(cookie) => F.pure(Right(cookie))
      case None =>
        transport.get(config.sessionUrl, Map("User-Agent" -> policy.config.userAgent)).flatMap {
          case Left(e) => F.pure(Left(e))
          case Right(resp) if resp.status / 100 == 2 =>
            VoilaSource.sessionCookieOf(resp) match {
              case Some(cookie) => session.set(Some(cookie)).as(Right(cookie))
              case None =>
                F.pure(Left(DealWatchError.Decode(name.value, "sessionId", "no session cookie in response")))
            }
          case Right(resp) => F.pure(Left(DealWatchError.HttpStatus(resp.status, config.sessionUrl)))
        }
    }

  private def search(query: String, locale: Locale, cookie: String): F[Either[DealWatchError, List[EnrichedPrice]]] = {
    val url = s"${config.baseUrl}?term=${URLEncoder.encode(query, StandardCharsets.UTF_8.name).replace("+", "%20")}" +
      s"&limit=${config.limit}&offset=0&sort=favorite"
    val headers = Map(
      "Cookie"          -> cookie,
      "User-Agent"      -> policy.config.userAgent,
      "Accept-Language" -> (if (locale == Locale.FrCa) "fr-CA" else "en-CA"),
    )

    policy.run(url)(transport.get(url, headers).map(_.flatMap(classify(url, _)))).flatMap {
      case Left(e) => F.pure(Left(e))
      case Right(resp) =>
        F.realTimeInstant.map { now =>
          io.circe.parser
            .parse(new String(resp.body, StandardCharsets.UTF_8))
            .leftMap(f => DealWatchError.Decode(name.value, "", s"not JSON: ${f.message}"): DealWatchError)
            .map(VoilaSource.decode(_, config.merchantId, name, now))
        }
    }
  }

  private def classify(url: String, resp: HttpResponse): Either[DealWatchError, HttpResponse] =
    if (resp.status / 100 == 2) Right(resp) else Left(DealWatchError.HttpStatus(resp.status, url))
}

object VoilaSource {

  def create[F[_]: Temporal](
      config: VoilaConfig,
      transport: HttpTransport[F],
      policy: HttpPolicy[F],
  ): F[VoilaSource[F]] =
    Ref.of[F, Option[String]](None).map(new VoilaSource(config, transport, policy, _))

  def sessionCookieOf(resp: HttpResponse): Option[String] = {
    val body = new String(resp.body, StandardCharsets.UTF_8)
    io.circe.parser.parse(body).toOption
      .flatMap(_.hcursor.get[String]("sessionId").toOption)
      .map(id => s"global_sid=$id")
      .orElse(Option(resp.contentType).filter(_.contains("VISITORID")))
  }

  def decode(json: Json, merchant: MerchantId, source: SourceName, now: java.time.Instant): List[EnrichedPrice] =
    json.hcursor.downField("entities").downField("product").values
      .orElse(json.hcursor.downField("products").values)
      .toList
      .flatMap(_.toList)
      .flatMap { p =>
        val c = p.hcursor
        c.get[String]("name").toOption.map { n =>
          EnrichedPrice(
            merchantId = merchant,
            name = BilingualText.enOnly(n),
            regularPrice = money(c.downField("price").downField("current").downField("amount").focus)
              .orElse(money(c.downField("price").focus)),
            salePrice = money(c.downField("price").downField("promotion").downField("amount").focus)
              .orElse(money(c.downField("offerPrice").focus)),
            unitPrice = None,
            source = source,
            // Online prices are a reference, not flyer truth (06.3)
            provenance = PriceProvenance.OnlineReference,
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
