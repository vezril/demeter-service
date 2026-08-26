package demeter.insight

import cats.effect.Concurrent
import cats.syntax.all._
import io.circe.Json
import io.circe.syntax._
import org.http4s._
import org.http4s.circe._
import org.http4s.dsl.Http4sDsl
import org.http4s.dsl.impl.OptionalQueryParamDecoderMatcher

import demeter.foundations.ProductKey

/** The HTTP surface.
  *
  * Every READ route is a GET and stays one; the test suite asserts it. The
  * watch-editing routes are the deliberate exception, and they exist only when
  * `writes` is configured -- see the comment above them for why that reversal
  * is narrow enough to be safe.
  */
final class Routes[F[_]: Concurrent](
    runs: RunQueries[F],
    history: HistoryQueries[F],
    watches: WatchQueries[F],
    /** Present only when watch editing is enabled. `None` keeps this service
      * exactly as read-only as it was before -- the write routes do not exist
      * at all rather than existing and refusing, so a deployment without the
      * write role cannot be talked into a write.
      */
    writes: Option[WatchWrites[F]] = None,
) extends Http4sDsl[F] {

  private object Limit       extends OptionalQueryParamDecoderMatcher[Int]("limit")
  private object ActiveParam extends OptionalQueryParamDecoderMatcher[Boolean]("active")

  /** Window in days, defaulted to the 8 weeks the deal verdict itself uses so
    * the chart and the alerts are talking about the same period. Clamped rather
    * than rejected: a nonsense window is a typo, not an attack.
    */
  private object WindowDays extends OptionalQueryParamDecoderMatcher[Int]("days")

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

    case GET -> Root / "v1" / "products" / productKey / "history" :? WindowDays(days) =>
      val window = java.time.Duration.ofDays(days.map(d => math.max(1, math.min(365, d.toLong))).getOrElse(56L))
      // ProductKey is a plain value class with no smart constructor, so the
      // only thing worth rejecting is a blank segment.
      if (productKey.trim.isEmpty) BadRequest(Map("error" -> "product key must not be blank").asJson)
      else
        history.forProduct(ProductKey(productKey), window).attempt.flatMap {
          // An empty series is 200, not 404. "No observations for this product"
          // is a data answer, and the alternative teaches a client that 404
          // means both "unknown key" and "nothing recorded yet".
          case Right(view) => Ok(view.asJson)
          case Left(_)     => ServiceUnavailable(Map("error" -> "database unavailable").asJson)
        }

    // --- watch editing (only mounted when `writes` is configured) ---
    //
    // This is the one part of the service that is not a GET. It reverses the
    // original "no writes" decision, but not the reason for it: the connection
    // behind these routes belongs to a role that can write watch_item and
    // nothing else. A watchlist can be retyped; the price history cannot,
    // because flyers expire.

    case req @ POST -> Root / "v1" / "watches" if writes.isDefined =>
      req.as[WatchRequest](Concurrent[F], jsonOf[F, WatchRequest]).attempt.flatMap {
        case Left(_) => BadRequest(Map("error" -> "could not parse the watch").asJson)
        case Right(request) =>
          writes.get.save(request).attempt.flatMap {
            case Right(Right(_)) => Created(Map("id" -> request.id).asJson)
            // A domain rejection is 422, not 400: the JSON was fine, the WATCH
            // was not, and the message says which rule it broke. A store failure
            // is 503 -- telling someone their watch is invalid when the database
            // is down sends them editing a form that was never wrong.
            case Right(Left(WriteFailure.Invalid(reason))) =>
              UnprocessableEntity(Map("error" -> reason).asJson)
            case Right(Left(WriteFailure.Unavailable(_))) | Left(_) =>
              ServiceUnavailable(Map("error" -> "database unavailable").asJson)
          }
      }

    case PATCH -> Root / "v1" / "watches" / id / "active" :? ActiveParam(active) if writes.isDefined =>
      writes.get.setActive(id, active.getOrElse(true)).attempt.flatMap {
        case Right(Right(true))  => NoContent()
        case Right(Right(false)) => NotFound(Map("error" -> s"no watch '$id'").asJson)
        case Right(Left(WriteFailure.Invalid(reason))) =>
          UnprocessableEntity(Map("error" -> reason).asJson)
        case Right(Left(WriteFailure.Unavailable(_))) | Left(_) =>
          ServiceUnavailable(Map("error" -> "database unavailable").asJson)
      }

    case DELETE -> Root / "v1" / "watches" / id if writes.isDefined =>
      writes.get.delete(id).attempt.flatMap {
        case Right(Right(true))  => NoContent()
        case Right(Right(false)) => NotFound(Map("error" -> s"no watch '$id'").asJson)
        case Right(Left(WriteFailure.Invalid(reason))) =>
          UnprocessableEntity(Map("error" -> reason).asJson)
        case Right(Left(WriteFailure.Unavailable(_))) | Left(_) =>
          ServiceUnavailable(Map("error" -> "database unavailable").asJson)
      }

    case GET -> Root / "v1" / "watches" =>
      watches.watches.attempt.flatMap {
        case Right(list) => Ok(list.asJson)
        case Left(_)     => ServiceUnavailable(Map("error" -> "database unavailable").asJson)
      }

    case GET -> Root / "v1" / "alerts" :? Limit(limit) =>
      val capped = limit.map(l => math.max(1, math.min(500, l))).getOrElse(50)
      watches.alerts(capped).attempt.flatMap {
        case Right(list) => Ok(list.asJson)
        case Left(_)     => ServiceUnavailable(Map("error" -> "database unavailable").asJson)
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
