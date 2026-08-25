package demeter.insight

import cats.effect.Concurrent
import cats.syntax.all._
import io.circe.syntax._
import org.http4s._
import org.http4s.circe._
import org.http4s.dsl.Http4sDsl

/** The read-only HTTP surface.
  *
  * Every route is a GET, and that is a design constraint rather than a current
  * state of affairs: a write path would drag in authentication, CSRF and an
  * audit story, none of which is worth it to save an INSERT into a household
  * tool. The test suite asserts it.
  */
final class Routes[F[_]: Concurrent](runs: RunQueries[F]) extends Http4sDsl[F] {

  val routes: HttpRoutes[F] = HttpRoutes.of[F] { case GET -> Root / "v1" / "runs" / "latest" =>
    runs.latest.attempt.flatMap {
      // No runs yet is 404 rather than an empty body: a client must be able to
      // tell "nothing has run" from "a run happened and had no alerts".
      case Right(Some(view)) => Ok(view.asJson)
      case Right(None)       => NotFound(Map("error" -> "no runs recorded yet").asJson)
      // The database being unreachable is 503, never an empty result. An
      // outage that renders as "no data" is how a broken view looks healthy.
      case Left(_) => ServiceUnavailable(Map("error" -> "database unavailable").asJson)
    }
  }
}
