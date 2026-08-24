package demeter.ingestion

import scala.concurrent.duration._

import cats.effect.kernel.{Ref, Temporal}
import cats.effect.std.Random
import cats.syntax.all._
import demeter.foundations.{DealWatchError, Locale}

/** Spec 01.6 — the one place that owns how we talk to undocumented endpoints
  * politely and defensibly: timeout, retry with full-jitter backoff, per-source
  * rate limiting, realistic headers, and bot-wall detection. All calls behind
  * this policy are GETs, so retries are always safe.
  */
final case class HttpPolicyConfig(
    timeout: FiniteDuration = 30.seconds,
    maxAttempts: Int = 3,
    backoffBase: FiniteDuration = 1.second,
    backoffCap: FiniteDuration = 30.seconds,
    rateLimit: Int = 4, // requests per rateWindow, per source
    rateWindow: FiniteDuration = 1.minute,
    userAgent: String = HttpPolicyConfig.DefaultUserAgent,
    botWallSignatures: List[String] = HttpPolicyConfig.DefaultBotWallSignatures,
)

object HttpPolicyConfig {
  val DefaultUserAgent: String =
    "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0 Safari/537.36"

  // Config-listed so new challenge-page signatures are an ops change, not a redeploy.
  val DefaultBotWallSignatures: List[String] =
    List("cf-chl", "cf_chl", "challenge-platform", "captcha", "cf-turnstile")
}

/** Pure backoff math, split from the clock so it can be property-tested.
  * attempt n wait = min(cap, base * 2^(n-1)) * random_in[0,1]   (full jitter)
  */
object Backoff {
  def wait(attempt: Int, base: FiniteDuration, cap: FiniteDuration, random: Double): FiniteDuration = {
    require(attempt >= 1, s"attempt must be >= 1, got $attempt")
    val ceiling = math.min(cap.toNanos.toDouble, base.toNanos.toDouble * math.pow(2.0, (attempt - 1).toDouble))
    (ceiling * random).toLong.nanos
  }
}

object BotWallDetection {

  /** A 403, or any response whose body carries a known challenge signature,
    * classifies as BotWall — non-retriable, operator attention (00.5).
    */
  def classify(status: Int, body: String, url: String, signatures: List[String]): Option[DealWatchError.BotWall] = {
    val marker = signatures.find(body.contains)
    if (status == 403) Some(DealWatchError.BotWall(url, marker.getOrElse("http-403")))
    else marker.map(DealWatchError.BotWall(url, _))
  }
}

object HeadersPolicy {
  def acceptLanguage(locale: Locale): String =
    locale match {
      case Locale.FrCa => "fr-CA,fr;q=0.9,en;q=0.5"
      case Locale.EnCa => "en-CA,en;q=0.9,fr;q=0.5"
    }
}

/** Sliding-window rate limiter. The scheduling decision is a pure function of
  * (now, recorded starts) so it can be tested without a clock; the effectful
  * wrapper applies it atomically, sleeps, then writes back the *actual* start
  * time. That write-back matters: sleeps wake a few milliseconds late, and if
  * later reservations chained off the stale planned time the drift would let an
  * extra request slip into a window.
  */
final class RateLimiter[F[_]] private (
    limit: Int,
    window: FiniteDuration,
    state: Ref[F, Vector[FiniteDuration]],
)(implicit F: Temporal[F]) {

  def acquire: F[Unit] =
    for {
      now     <- F.monotonic
      planned <- state.modify(starts => RateLimiter.plan(now, starts, limit, window))
      _       <- F.whenA(planned > now)(F.sleep(planned - now))
      actual  <- F.monotonic
      _       <- F.whenA(actual > planned)(state.update(RateLimiter.correct(_, planned, actual)))
    } yield ()
}

object RateLimiter {

  def create[F[_]: Temporal](limit: Int, window: FiniteDuration): F[RateLimiter[F]] =
    Ref.of[F, Vector[FiniteDuration]](Vector.empty).map(new RateLimiter(limit, window, _))

  /** Given the current time and the already-recorded start times, returns the
    * absolute time the next request may start and the updated start list.
    * Guarantees no more than `limit` starts within any `window`.
    */
  def plan(
      now: FiniteDuration,
      starts: Vector[FiniteDuration],
      limit: Int,
      window: FiniteDuration,
  ): (Vector[FiniteDuration], FiniteDuration) = {
    val active = starts.filter(_ + window > now).sorted
    val start  = if (active.size < limit) now else active(active.size - limit) + window
    (active :+ start, start)
  }

  /** Replace one recorded planned start with the time the request actually began. */
  def correct(
      starts: Vector[FiniteDuration],
      planned: FiniteDuration,
      actual: FiniteDuration,
  ): Vector[FiniteDuration] =
    starts.indexOf(planned) match {
      case -1 => starts
      case i  => starts.updated(i, actual)
    }
}

/** Retry + timeout + rate limiting around one attempted request. */
final class HttpPolicy[F[_]] private (
    val config: HttpPolicyConfig,
    limiter: RateLimiter[F],
)(implicit F: Temporal[F], R: Random[F]) {

  def headers(locale: Locale): Map[String, String] =
    Map(
      "User-Agent"      -> config.userAgent,
      "Accept"          -> "application/json",
      "Accept-Language" -> HeadersPolicy.acceptLanguage(locale),
    )

  def run[A](url: String)(attempt: F[Either[DealWatchError, A]]): F[Either[DealWatchError, A]] = {
    val once: F[Either[DealWatchError, A]] =
      limiter.acquire *> F.timeoutTo(
        attempt,
        config.timeout,
        F.pure(Left(DealWatchError.Timeout(url)): Either[DealWatchError, A]),
      )

    def go(n: Int): F[Either[DealWatchError, A]] =
      once.flatMap {
        case Left(e) if e.retriable && n < config.maxAttempts =>
          R.nextDouble.flatMap(r => F.sleep(Backoff.wait(n, config.backoffBase, config.backoffCap, r))) *> go(n + 1)
        case done => F.pure(done)
      }

    go(1)
  }
}

object HttpPolicy {
  def create[F[_]: Temporal: Random](config: HttpPolicyConfig): F[HttpPolicy[F]] =
    RateLimiter.create[F](config.rateLimit, config.rateWindow).map(new HttpPolicy(config, _))
}
