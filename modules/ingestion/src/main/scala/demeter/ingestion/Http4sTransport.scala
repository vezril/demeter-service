package demeter.ingestion

import java.util.concurrent.TimeoutException

import cats.effect.kernel.Async
import cats.syntax.all._
import demeter.foundations.DealWatchError
import org.http4s.{Header, Headers, Method, Request, Uri}
import org.http4s.client.Client
import org.typelevel.ci.CIString

/** Thin http4s-backed transport. Deliberately dumb: no retry, no policy — the
  * HttpPolicy owns behaviour; this only performs one GET and captures bytes.
  */
object Http4sTransport {

  def apply[F[_]](client: Client[F])(implicit F: Async[F]): HttpTransport[F] =
    new HttpTransport[F] {
      def get(url: String, headers: Map[String, String]): F[Either[DealWatchError, HttpResponse]] =
        Uri.fromString(url) match {
          case Left(err) => F.pure(Left(DealWatchError.Transport(url, s"bad URI: ${err.message}")))
          case Right(uri) =>
            val request = Request[F](
              method = Method.GET,
              uri = uri,
              headers = Headers(headers.toList.map { case (k, v) => Header.Raw(CIString(k), v) }),
            )
            client
              .run(request)
              .use { resp =>
                resp.body.compile.to(Array).map { bytes =>
                  val contentType =
                    resp.headers.get(CIString("Content-Type")).map(_.head.value).getOrElse("application/octet-stream")
                  Right(HttpResponse(resp.status.code, bytes, contentType)): Either[DealWatchError, HttpResponse]
                }
              }
              .recover {
                case _: TimeoutException => Left(DealWatchError.Timeout(url))
                case e                   => Left(DealWatchError.Transport(url, e.toString))
              }
        }
    }
}
