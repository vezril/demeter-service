package demeter.enrichment

import java.nio.charset.StandardCharsets
import java.time.Instant

import scala.concurrent.duration._

import cats.effect.IO
import cats.effect.kernel.Ref
import cats.effect.std.Random
import cats.effect.unsafe.implicits.global
import demeter.foundations._
import demeter.ingestion._
import org.scalatest.funsuite.AnyFunSuite

/** Specs 06.1–06.4 — enrichment sources against stub transports.
  *
  * The @contract scenarios that need REAL captured responses are pending until
  * the operator captures them (they need a PC Express key / Voilà session);
  * see task 9.2. Everything testable without those fixtures is covered here.
  */
final class EnrichmentSourcesSpec extends AnyFunSuite {

  private val postal = PostalCode.parse("H2X1Y6").toOption.get
  private val config = HttpPolicyConfig(backoffBase = 1.milli, backoffCap = 2.millis, rateLimit = 1000, rateWindow = 1.second)

  private def policy: HttpPolicy[IO] =
    Random.scalaUtilRandom[IO].flatMap(implicit r => HttpPolicy.create[IO](config)).unsafeRunSync()

  private def bytes(s: String) = s.getBytes(StandardCharsets.UTF_8)

  private def getStub(status: Int, body: String, log: Option[Ref[IO, List[String]]] = None): HttpTransport[IO] =
    (url, _) => log.fold(IO.unit)(_.update(_ :+ url)).as(Right(HttpResponse(status, bytes(body), "application/json")))

  /** Voilà answers /sessions and /products/search differently, as the real backend does. */
  private def voilaStub(
      searchStatus: Int,
      searchBody: String,
      sessionStatus: Int = 200,
      log: Option[Ref[IO, List[String]]] = None,
  ): HttpTransport[IO] =
    (url, _) =>
      log.fold(IO.unit)(_.update(_ :+ url)).as {
        if (url.contains("/sessions")) Right(HttpResponse(sessionStatus, bytes("""{"sessionId":"abc"}"""), "application/json"))
        else Right(HttpResponse(searchStatus, bytes(searchBody), "application/json"))
      }

  private def postStub(
      status: Int,
      body: String,
      log: Ref[IO, List[(String, Map[String, String], String)]],
  ): PostTransport[IO] =
    (url, headers, reqBody) => log.update(_ :+ ((url, headers, reqBody))).as(Right(HttpResponse(status, bytes(body), "application/json")))

  // --- 06.2 PC Express ---

  private val pcBanners = Map(MerchantId(1) -> "maxi", MerchantId(2) -> "provigo")

  test("a product search response maps to enriched prices") {
    val body = """{"results":[{"name":"Lait Natrel 4 L","prices":{"price":{"value":4.99}}}]}"""
    val log  = Ref.of[IO, List[(String, Map[String, String], String)]](Nil).unsafeRunSync()
    val src  = new PcExpressSource[IO](PcExpressConfig("KEY", banners = Map(MerchantId(1) -> "maxi")), postStub(200, body, log), policy)

    val Right(prices) = src.lookup("lait", postal, Locale.FrCa).unsafeRunSync()
    assert(prices.size == 1)
    assert(prices.head.regularPrice.contains(Money.cents(499)))
    assert(prices.head.merchantId == MerchantId(1))
    assert(prices.head.provenance == PriceProvenance.Shelf)
  }

  test("the banner header is set from the target merchant and the key comes from config") {
    val log = Ref.of[IO, List[(String, Map[String, String], String)]](Nil).unsafeRunSync()
    val src = new PcExpressSource[IO](PcExpressConfig("SECRET-KEY", banners = pcBanners), postStub(200, """{"results":[]}""", log), policy)

    src.lookupBanner("lait", postal, Locale.FrCa, MerchantId(1)).unsafeRunSync()
    src.lookupBanner("lait", postal, Locale.FrCa, MerchantId(2)).unsafeRunSync()

    val banners = log.get.unsafeRunSync().map(_._2("Site-Banner"))
    assert(banners == List("maxi", "provigo"))
    assert(log.get.unsafeRunSync().forall(_._2("X-Apikey") == "SECRET-KEY"))
  }

  test("the request body carries the term, banner, and language") {
    val log = Ref.of[IO, List[(String, Map[String, String], String)]](Nil).unsafeRunSync()
    val src = new PcExpressSource[IO](PcExpressConfig("K", banners = Map(MerchantId(1) -> "maxi")), postStub(200, """{"results":[]}""", log), policy)
    src.lookup("lait", postal, Locale.FrCa).unsafeRunSync()
    val body = log.get.unsafeRunSync().head._3
    assert(body.contains("\"term\":\"lait\""))
    assert(body.contains("\"banner\":\"maxi\""))
    assert(body.contains("\"lang\":\"fr\""))
  }

  test("a rejected API key degrades this source with a typed error") {
    val log = Ref.of[IO, List[(String, Map[String, String], String)]](Nil).unsafeRunSync()
    val src = new PcExpressSource[IO](PcExpressConfig("BAD", banners = Map(MerchantId(1) -> "maxi")), postStub(401, "unauthorized", log), policy)
    val result = src.lookup("lait", postal, Locale.EnCa).unsafeRunSync()
    assert(result == Left(DealWatchError.HttpStatus(401, PcExpressConfig("BAD").baseUrl)))
    assert(!result.swap.toOption.get.retriable) // a bad key is not worth retrying
  }

  test("the API key is never a source literal") {
    val src = new PcExpressSource[IO](PcExpressConfig("FROM-CONFIG", banners = pcBanners), postStub(200, "{}", Ref.of[IO, List[(String, Map[String, String], String)]](Nil).unsafeRunSync()), policy)
    assert(src.headers("maxi", Locale.EnCa)("X-Apikey") == "FROM-CONFIG")
  }

  // --- 06.3 Voilà ---

  test("a session is established before searching") {
    val urls = Ref.of[IO, List[String]](Nil).unsafeRunSync()
    val src  = VoilaSource.create[IO](VoilaConfig(), voilaStub(200, """{"products":[]}""", log = Some(urls)), policy).unsafeRunSync()
    src.lookup("lait", postal, Locale.FrCa).unsafeRunSync()
    val seen = urls.get.unsafeRunSync()
    assert(seen.head.contains("/sessions"))
    assert(seen(1).contains("/products/search"))
  }

  test("an expired session is re-established once, and a second 401 degrades the source") {
    val attempts = Ref.of[IO, Int](0).unsafeRunSync()
    // sessions always succeed; searches always 401
    val transport: HttpTransport[IO] = (url, _) =>
      if (url.contains("/sessions")) IO.pure(Right(HttpResponse(200, bytes("""{"sessionId":"s"}"""), "application/json")))
      else attempts.update(_ + 1).as(Right(HttpResponse(401, bytes("expired"), "application/json")))

    val src    = VoilaSource.create[IO](VoilaConfig(), transport, policy).unsafeRunSync()
    val result = src.lookup("lait", postal, Locale.FrCa).unsafeRunSync()
    assert(result == Left(DealWatchError.HttpStatus(401, VoilaConfig().baseUrl + "?term=lait&limit=20&offset=0&sort=favorite")))
    assert(attempts.get.unsafeRunSync() == 2) // original + exactly one retry
  }

  test("Voila prices are marked as an online reference, not flyer truth") {
    val body = """{"products":[{"name":"Lait 4 L","price":{"current":{"amount":5.49}}}]}"""
    val src  = VoilaSource.create[IO](VoilaConfig(), voilaStub(200, body), policy).unsafeRunSync()
    val Right(prices) = src.lookup("lait", postal, Locale.FrCa).unsafeRunSync()
    assert(prices.head.provenance == PriceProvenance.OnlineReference)
    assert(prices.head.regularPrice.contains(Money.cents(549)))
  }

  // --- 06.4 Canadian Tire ---

  test("CT enrichment is cached for the flyer validity window") {
    val calls = Ref.of[IO, List[String]](Nil).unsafeRunSync()
    val body  = """{"skus":[{"title":"MASTERCRAFT Socket Set","currentPrice":{"value":29.99}}]}"""
    val src   = CanadianTireSource.create[IO](CanadianTireConfig(), getStub(200, body, Some(calls)), policy).unsafeRunSync()

    val until = Instant.now().plusSeconds(86400)
    val first = src.lookupCachedUntil("socket set", postal, Locale.EnCa, Some(until)).unsafeRunSync()
    val again = src.lookupCachedUntil("socket set", postal, Locale.EnCa, Some(until)).unsafeRunSync()

    assert(first == again)
    assert(first.toOption.get.head.regularPrice.contains(Money.cents(2999)))
    assert(calls.get.unsafeRunSync().size == 1, "the second lookup must be served from cache")
  }

  test("an expired cache entry re-fetches") {
    val calls = Ref.of[IO, List[String]](Nil).unsafeRunSync()
    val src   = CanadianTireSource.create[IO](CanadianTireConfig(), getStub(200, """{"skus":[]}""", Some(calls)), policy).unsafeRunSync()
    val past  = Instant.now().minusSeconds(60)
    src.lookupCachedUntil("x", postal, Locale.EnCa, Some(past)).unsafeRunSync()
    src.lookupCachedUntil("x", postal, Locale.EnCa, Some(past)).unsafeRunSync()
    assert(calls.get.unsafeRunSync().size == 2)
  }

  test("CT lookups are serialized, never run in parallel") {
    val inFlight = Ref.of[IO, Int](0).unsafeRunSync()
    val maxSeen  = Ref.of[IO, Int](0).unsafeRunSync()
    val transport: HttpTransport[IO] = (_, _) =>
      inFlight.updateAndGet(_ + 1).flatMap(n => maxSeen.update(_ max n)) *>
        IO.sleep(20.millis) *>
        inFlight.update(_ - 1).as(Right(HttpResponse(200, bytes("""{"skus":[]}"""), "application/json")))

    val src = CanadianTireSource.create[IO](CanadianTireConfig(), transport, policy).unsafeRunSync()
    import cats.syntax.all._
    List("a", "b", "c").parTraverse(q => src.lookup(q, postal, Locale.EnCa)).unsafeRunSync()
    assert(maxSeen.get.unsafeRunSync() == 1, "CT must never fan out in parallel")
  }

  test("CT's rate limit is stricter than the grocery sources'") {
    val groceryPerMinute = HttpPolicyConfig().rateLimit.toDouble / HttpPolicyConfig().rateWindow.toMinutes.max(1)
    val ctPerMinute      = CanadianTireSource.StricterRateLimit.toDouble / CanadianTireSource.StricterRateWindow.toMinutes.max(1)
    assert(ctPerMinute < groceryPerMinute)
  }

  // --- 06.1 interface semantics ---

  test("a source declares which merchants it can enrich") {
    val src = new PcExpressSource[IO](PcExpressConfig("K", banners = pcBanners), postStub(200, "{}", Ref.of[IO, List[(String, Map[String, String], String)]](Nil).unsafeRunSync()), policy)
    assert(src.merchantsCovered == Set(MerchantId(1), MerchantId(2)))
  }

  test("an enrichment failure is a typed value the run can continue past") {
    val src    = VoilaSource.create[IO](VoilaConfig(), voilaStub(503, "down"), policy).unsafeRunSync()
    val result = src.lookup("lait", postal, Locale.FrCa).unsafeRunSync()
    assert(result.isLeft)
    // non-blocking by construction: the caller gets a value, not an exception
    assert(result.swap.exists(_.retriable))
  }

  test("a transient outage while establishing the session stays retriable, not a synthetic 401") {
    val src    = VoilaSource.create[IO](VoilaConfig(), voilaStub(200, "{}", sessionStatus = 503), policy).unsafeRunSync()
    val result = src.ensureSession.unsafeRunSync()
    assert(result == Left(DealWatchError.HttpStatus(503, VoilaConfig().sessionUrl)))
    assert(result.swap.exists(_.retriable), "a 503 must not be mistaken for a rejected session")
  }
}
