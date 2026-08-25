package demeter.orchestration

import cats.effect.kernel.Concurrent
import cats.syntax.all._
import org.http4s.client.Client
import org.http4s.{Header, MediaType, Method, Request, Uri}
import org.http4s.headers.`Content-Type`
import org.typelevel.ci.CIString

import demeter.foundations.DealWatchError

/** Spec 05.4 — the POST behind every HTTP sink.
  *
  * Delivery is judged on the response STATUS, never on decoding the body.
  * `expect[String]` looks equivalent and is not: its decoder accepts only
  * the text media range, so an endpoint answering 2xx with Content-Type: application/json --
  * which HermesMQ and ntfy both do -- turns a successful publish into a
  * transport failure. The alert really goes out; the run records a failure,
  * counts zero delivered, and never writes the ledger row that dedup is keyed
  * on, so every subsequent run re-alerts the same deals. That is the exact
  * outcome 05.2 exists to prevent, arrived at through a content-type mismatch.
  */
object HttpDelivery {

  def sender[F[_]: Concurrent](
      client: Client[F]
  ): (String, String, Option[String], Boolean) => F[Either[DealWatchError, Unit]] =
    (url, body, token, json) =>
      Uri.fromString(url) match {
        case Left(e) => Concurrent[F].pure(Left(DealWatchError.Transport(url, e.message)))
        case Right(uri) =>
          val base  = Request[F](Method.POST, uri).withEntity(body)
          val typed = if (json) base.putHeaders(`Content-Type`(MediaType.application.json)) else base
          val req   = token.fold(typed)(t => typed.putHeaders(Header.Raw(CIString("Authorization"), s"Bearer $t")))
          client
            .run(req)
            .use { response =>
              if (response.status.isSuccess) Concurrent[F].pure(Right(()): Either[DealWatchError, Unit])
              else
                // Read only to explain a failure, where a decode problem cannot
                // cost a delivery that already happened.
                response.bodyText.compile.string.attempt.map { b =>
                  Left(DealWatchError.Transport(url, s"HTTP ${response.status.code}: ${b.getOrElse("")}")): Either[
                    DealWatchError,
                    Unit,
                  ]
                }
            }
            .handleError(e => Left(DealWatchError.Transport(url, e.toString)))
      }
}
