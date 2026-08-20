package demeter.ingestion

import scala.concurrent.duration._

import cats.effect.IO
import cats.effect.kernel.Ref
import cats.effect.std.Random
import cats.effect.unsafe.implicits.global
import cats.syntax.all._
import demeter.foundations.{DealWatchError, Locale}
import org.scalacheck.Gen
import org.scalatest.funsuite.AnyFunSuite
import org.scalatestplus.scalacheck.ScalaCheckPropertyChecks

/** Spec 01.6 — polite, resilient HTTP policy. Backoff math is @pure/@property;
  * retry behaviour is @boundary against stub attempts (tiny backoff base).
  */
final class HttpPolicySpec extends AnyFunSuite with ScalaCheckPropertyChecks {

  private val fastConfig = HttpPolicyConfig(
    timeout = 2.seconds,
    maxAttempts = 3,
    backoffBase = 1.milli,
    backoffCap = 5.millis,
    rateLimit = 1000,
    rateWindow = 1.second,
  )

  private def policy(config: HttpPolicyConfig = fastConfig): IO[HttpPolicy[IO]] =
    Random.scalaUtilRandom[IO].flatMap(implicit r => HttpPolicy.create[IO](config))

  private def counted[A](results: List[Either[DealWatchError, A]]): IO[(Ref[IO, Int], IO[Either[DealWatchError, A]])] =
    Ref.of[IO, Int](0).map { counter =>
      val attempt = counter.updateAndGet(_ + 1).map(n => results(math.min(n, results.size) - 1))
      (counter, attempt)
    }

  test("a retriable error is retried up to the attempt cap then surfaced") {
    val err = DealWatchError.HttpStatus(503, "u")
    val (attempts, result) = (for {
      p        <- policy()
      ca       <- counted[Unit](List(Left(err), Left(err), Left(err)))
      (c, a)    = ca
      res      <- p.run("u")(a)
      n        <- c.get
    } yield (n, res)).unsafeRunSync()
    assert(attempts == 3)
    assert(result == Left(err))
  }

  test("a non-retriable error is not retried") {
    val err = DealWatchError.HttpStatus(404, "u")
    val (attempts, result) = (for {
      p     <- policy()
      ca    <- counted[Unit](List(Left(err)))
      (c, a) = ca
      res   <- p.run("u")(a)
      n     <- c.get
    } yield (n, res)).unsafeRunSync()
    assert(attempts == 1)
    assert(result == Left(err))
  }

  test("a success on the second attempt returns without further tries") {
    val (attempts, result) = (for {
      p     <- policy()
      ca    <- counted[String](List(Left(DealWatchError.Timeout("u")), Right("ok"), Right("never")))
      (c, a) = ca
      res   <- p.run("u")(a)
      n     <- c.get
    } yield (n, res)).unsafeRunSync()
    assert(attempts == 2)
    assert(result == Right("ok"))
  }

  test("a Cloudflare challenge short-circuits to BotWall without retry") {
    val err = DealWatchError.BotWall("u", "cf-chl-bypass")
    val (attempts, result) = (for {
      p     <- policy()
      ca    <- counted[Unit](List(Left(err)))
      (c, a) = ca
      res   <- p.run("u")(a)
      n     <- c.get
    } yield (n, res)).unsafeRunSync()
    assert(attempts == 1)
    assert(result == Left(err))
  }

  test("backoff wait stays within the jittered bound (property)") {
    val base = 1.second
    val cap  = 30.seconds
    forAll(Gen.choose(1, 8), Gen.choose(0.0, 1.0)) { (n, r) =>
      val w       = Backoff.wait(n, base, cap, r)
      val ceiling = math.min(cap.toNanos.toDouble, base.toNanos.toDouble * math.pow(2.0, (n - 1).toDouble))
      assert(w >= Duration.Zero)
      assert(w.toNanos <= ceiling.toLong)
    }
  }

  test("the rate limiter plan never allows more than the limit within any window (pure core)") {
    val window = 1.second
    // 5 back-to-back acquires at t=0 with limit 2 start at: 0, 0, 1s, 1s, 2s
    val (planned, _) = (1 to 5).foldLeft((Vector.empty[FiniteDuration], Vector.empty[FiniteDuration])) {
      case ((acc, starts), _) =>
        val (next, start) = RateLimiter.plan(Duration.Zero, starts, limit = 2, window = window)
        (acc :+ start, next)
    }
    assert(planned == Vector(Duration.Zero, Duration.Zero, 1.second, 1.second, 2.seconds))
  }

  test("a late actual start is corrected in the ledger so the next reservation chains off reality") {
    val starts = Vector(100.millis, 200.millis)
    assert(RateLimiter.correct(starts, 200.millis, 205.millis) == Vector(100.millis, 205.millis))
    assert(RateLimiter.correct(starts, 999.millis, 1.second) == starts) // unknown planned time: no-op
  }

  test("the rate limiter serializes bursts within a source (integration)") {
    val starts = (for {
      limiter <- RateLimiter.create[IO](limit = 2, window = 200.millis)
      times   <- (1 to 5).toList.traverse(_ => limiter.acquire *> IO.monotonic)
    } yield times).unsafeRunSync()

    // no more than 2 starts within any single window
    for (t <- starts) {
      val inWindow = starts.count(s => s >= t && s < t + 200.millis)
      assert(inWindow <= 2, s"starts: $starts")
    }
  }

  test("Accept-Language matches the requested locale") {
    val p = policy().unsafeRunSync()
    assert(p.headers(Locale.FrCa)("Accept-Language").startsWith("fr"))
    assert(p.headers(Locale.EnCa)("Accept-Language").startsWith("en"))
    assert(p.headers(Locale.EnCa).contains("User-Agent"))
  }

  test("bot-wall detection classifies 403 and signature-marked bodies") {
    val sigs = HttpPolicyConfig.DefaultBotWallSignatures
    assert(BotWallDetection.classify(403, "<html>denied</html>", "u", sigs).isDefined)
    assert(BotWallDetection.classify(429, "cf-chl-bypass challenge", "u", sigs).exists(_.signal == "cf-chl"))
    assert(BotWallDetection.classify(429, "slow down", "u", sigs).isEmpty)
    assert(BotWallDetection.classify(200, """{"flyers":[]}""", "u", sigs).isEmpty)
  }
}
