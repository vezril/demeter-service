package demeter.insight

import cats.effect.Concurrent
import cats.syntax.all._
import io.circe.Json
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

  val routes: HttpRoutes[F] = HttpRoutes.of[F] {

    // An index at the root, because a bookmark should not 404.
    //
    // Not a redirect: /v1/runs/latest legitimately answers 404 until the first
    // daily run has been recorded, so redirecting there would send a new
    // deployment's very first visitor to an error. /health would work but reads
    // as a landing page for nobody. This says what the service is and what it
    // serves, which is the honest answer for an API with no UI yet.
    case GET -> Root =>
      Ok(
        Json.obj(
          "service"     -> "demeter-insight".asJson,
          "description" -> "read-only view of demeter's price history and run reports".asJson,
          "endpoints" -> Json
            .obj(
              "GET /health"         -> "200 when the database is reachable, 503 when not".asJson,
              "GET /v1/runs/latest" -> "the most recent daily run's report; 404 before the first run".asJson,
            )
            .asJson,
        )
      )

    // Readiness, and deliberately NOT /v1/runs/latest.
    //
    // Probing the data endpoint conflates "can serve" with "has data": before
    // the first daily run it answers 404, a pod probing it never becomes Ready,
    // and a service that is working perfectly is held out of rotation until
    // tomorrow morning. This asks the only question a probe should ask: is the
    // database answering?
    case GET -> Root / "health" =>
      runs.reachable.flatMap {
        case true  => Ok(Map("status" -> "UP").asJson)
        case false => ServiceUnavailable(Map("status" -> "DOWN", "reason" -> "database unreachable").asJson)
      }

    case GET -> Root / "v1" / "runs" / "latest" =>
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
