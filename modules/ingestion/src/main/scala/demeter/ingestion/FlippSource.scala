package demeter.ingestion

import java.net.URLEncoder
import java.nio.charset.StandardCharsets

import cats.effect.kernel.Temporal
import cats.syntax.all._
import demeter.foundations._

/** Minimal transport abstraction: the adapters and policy are tested against
  * stubs; the http4s-backed implementation (Http4sTransport) stays thin.
  */
final case class HttpResponse(status: Int, body: Array[Byte], contentType: String)

trait HttpTransport[F[_]] {
  def get(url: String, headers: Map[String, String]): F[Either[DealWatchError, HttpResponse]]
}

/** Specs 01.2 / 01.3 / 01.4 — typed request building for the Flipp endpoints.
  * A caller cannot pass a malformed postal code or locale; the types prevent it.
  */
object FlippUrls {
  val DefaultBase = "https://backflipp.wishabi.com/flipp"

  private def encode(s: String): String =
    URLEncoder.encode(s, StandardCharsets.UTF_8.name).replace("+", "%20")

  def flyers(base: String, postal: PostalCode, locale: Locale): String =
    s"$base/flyers?locale=${locale.queryValue}&postal_code=${postal.canonical}"

  def items(base: String, flyerId: FlyerId, postal: PostalCode, locale: Locale): String =
    s"$base/flyers/${flyerId.value}?locale=${locale.queryValue}&postal_code=${postal.canonical}"

  def search(base: String, term: String, postal: PostalCode, locale: Locale): String =
    s"$base/items/search?locale=${locale.queryValue}&postal_code=${postal.canonical}&q=${encode(term)}"
}

/** The Flipp backend adapted to the FlyerSource seam (01.2–01.4).
  *
  * Deliberately dumb: no merchant filtering (the orchestrator selects, 08.1),
  * no price parsing (normalization's job, 02), faithful raw bytes alongside
  * every parse. Status mapping happens inside the attempted effect so the
  * policy's retry loop sees typed errors (a BotWall short-circuits, a 503
  * retries).
  */
final class FlippSource[F[_]](
    transport: HttpTransport[F],
    policy: HttpPolicy[F],
    baseUrl: String = FlippUrls.DefaultBase,
)(implicit F: Temporal[F])
    extends FlyerSource[F] {

  val name: SourceName              = SourceName("flipp")
  val capabilities: Set[Capability] = Set(Capability.Flyers, Capability.Items, Capability.Search)

  def flyers(postal: PostalCode, locale: Locale): F[Either[DealWatchError, RawFlyerListing]] =
    call(FlippUrls.flyers(baseUrl, postal, locale), locale) { raw =>
      for {
        json   <- FlippDecoders.parseJson(name.value, raw.bytes)
        parsed <- FlippDecoders.decodeListing(name.value, json)
      } yield RawFlyerListing(raw, parsed.flyers, parsed.merchants, parsed.dropped)
    }

  def items(flyerId: FlyerId, postal: PostalCode, locale: Locale): F[Either[DealWatchError, RawFlyerItems]] =
    call(FlippUrls.items(baseUrl, flyerId, postal, locale), locale) { raw =>
      for {
        json   <- FlippDecoders.parseJson(name.value, raw.bytes)
        parsed <- FlippDecoders.decodeItems(name.value, json)
      } yield RawFlyerItems(raw, parsed.items, parsed.dropped)
    }

  def search(term: String, postal: PostalCode, locale: Locale): F[Either[DealWatchError, RawSearchResult]] =
    call(FlippUrls.search(baseUrl, term, postal, locale), locale) { raw =>
      for {
        json   <- FlippDecoders.parseJson(name.value, raw.bytes)
        parsed <- FlippDecoders.decodeSearch(name.value, json)
      } yield RawSearchResult(
        raw,
        parsed.flyerItems,
        parsed.ecomItems,
        parsed.merchants,
        parsed.normalizedQuery,
        parsed.dropped,
      )
    }

  private def call[A](url: String, locale: Locale)(
      decode: RawResponse => Either[DealWatchError, A]
  ): F[Either[DealWatchError, A]] =
    policy
      .run(url)(transport.get(url, policy.headers(locale)).map(_.flatMap(classify(url, _))))
      .flatMap {
        case Left(e) => F.pure(Left(e))
        case Right(resp) =>
          F.realTimeInstant.map(now => decode(RawResponse(resp.body, resp.contentType, now, url)))
      }

  private def classify(url: String, resp: HttpResponse): Either[DealWatchError, HttpResponse] = {
    val body = new String(resp.body, StandardCharsets.UTF_8)
    BotWallDetection.classify(resp.status, body, url, policy.config.botWallSignatures) match {
      case Some(botWall)                  => Left(botWall)
      case None if resp.status / 100 == 2 => Right(resp)
      case None                           => Left(DealWatchError.HttpStatus(resp.status, url))
    }
  }
}
