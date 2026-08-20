package demeter.enrichment

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path, Paths}
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
  * Voilà is tested against a REAL captured page (fixtures/voila_search.sample.html,
  * 2026-08-20) and is the only enrichment source whose shape has been verified.
  *
  * PC Express and Canadian Tire are still written against assumed schemas that
  * live verification has since falsified — see
  * specs/06-enrichment/06.0-endpoint-verification.md. Their tests below prove
  * the plumbing (headers, key handling, degradation), NOT that the decoders
  * match reality. Neither should be enabled in a real run.
  */
final class EnrichmentSourcesSpec extends AnyFunSuite {

  private val postal = PostalCode.parse("H2X1Y6").toOption.get
  private val config = HttpPolicyConfig(backoffBase = 1.milli, backoffCap = 2.millis, rateLimit = 1000, rateWindow = 1.second)

  private def policy: HttpPolicy[IO] =
    Random.scalaUtilRandom[IO].flatMap(implicit r => HttpPolicy.create[IO](config)).unsafeRunSync()

  private def bytes(s: String) = s.getBytes(StandardCharsets.UTF_8)

  /** repo-root fixtures/, wherever sbt runs tests from */
  private def fixture(name: String): Path =
    List(Paths.get("fixtures", name), Paths.get("..", "..", "fixtures", name))
      .find(Files.exists(_))
      .getOrElse(sys.error(s"fixture not found: $name"))

  private def getStub(status: Int, body: String, log: Option[Ref[IO, List[String]]] = None): HttpTransport[IO] =
    (url, _) => log.fold(IO.unit)(_.update(_ :+ url)).as(Right(HttpResponse(status, bytes(body), "application/json")))

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

  // --- 06.3 Voilà (rebuilt 2026-08-20 against the verified shape) ---

  test("a captured search page decodes to enriched prices (@contract)") {
    val html   = new String(java.nio.file.Files.readAllBytes(fixture("voila_search.sample.html")), StandardCharsets.UTF_8)
    val Right(prices) = VoilaSource.decodePage(html, MerchantId(4592), SourceName("voila"), Instant.EPOCH)

    assert(prices.size == 4)
    assert(prices.forall(_.provenance == PriceProvenance.OnlineReference))

    val natrel = prices.find(_.name.anyForm.exists(_.startsWith("Natrel 2%"))).get
    assert(natrel.regularPrice.contains(Money.cents(569)))
    assert(natrel.salePrice.isEmpty, "not on sale: current IS the regular price")
    // "fop.price.per.100ml" at $0.28 becomes $2.80/L
    assert(natrel.unitPrice.map(_.price.cents).contains(280L))
    assert(natrel.unitPrice.map(_.per).contains(StdUnit.PerLitre))
  }

  test("a product on sale reports the regular price as the baseline, not the sale price") {
    val html   = new String(java.nio.file.Files.readAllBytes(fixture("voila_search.sample.html")), StandardCharsets.UTF_8)
    val Right(prices) = VoilaSource.decodePage(html, MerchantId(4592), SourceName("voila"), Instant.EPOCH)

    // this is the entire point of enrichment: 07.3 needs the REGULAR price to
    // judge whether a flyer's "sale" is real
    val onSale = prices.filter(_.salePrice.isDefined)
    assert(onSale.nonEmpty, "the fixture includes on-sale products")
    onSale.foreach { p =>
      assert(p.regularPrice.exists(r => p.salePrice.exists(_.cents < r.cents)), s"sale must undercut regular: $p")
    }
    val lactose = prices.find(_.name.anyForm.exists(_.contains("Natrel Plus"))).get
    assert(lactose.regularPrice.contains(Money.cents(899)))
    assert(lactose.salePrice.contains(Money.cents(699)))
  }

  test("the unit basis is converted, and an unrecognised basis yields no unit price rather than a wrong one") {
    assert(VoilaSource.unitBasisOf("fop.price.per.100ml").contains((StdUnit.PerLitre, BigDecimal(10))))
    assert(VoilaSource.unitBasisOf("fop.price.per.100g").contains((StdUnit.PerKg, BigDecimal(10))))
    assert(VoilaSource.unitBasisOf("fop.price.per.kg").contains((StdUnit.PerKg, BigDecimal(1))))
    assert(VoilaSource.unitBasisOf("fop.price.per.furlong").isEmpty, "a guessed basis is worse than none")
  }

  test("the search URL goes straight to the redirect target") {
    assert(VoilaSource.searchUrl("https://voila.ca", "lait") == "https://voila.ca/search?q=lait")
    assert(VoilaSource.searchUrl("https://voila.ca", "ground beef").endsWith("q=ground%20beef"))
  }

  test("a page without the embedded state is a decode error naming what was missing") {
    val result = VoilaSource.decodePage("<html><body>nothing here</body></html>", MerchantId(4592), SourceName("voila"), Instant.EPOCH)
    assert(result.swap.exists(_.isInstanceOf[DealWatchError.Decode]))
    assert(result.swap.exists(_.context("pointer") == "window.__INITIAL_STATE__"))
  }

  test("an individual unparseable product is dropped, not fatal to the lookup") {
    val html = """<script>window.__INITIAL_STATE__ = {"data":{"products":{"productEntities":{
      "a":{"name":"Good Milk 2 L","price":{"current":{"amount":"4.99","currency":"CAD"}}},
      "b":{"price":{"current":{"amount":"1.99","currency":"CAD"}}}
    }}}};</script>"""
    val Right(prices) = VoilaSource.decodePage(html, MerchantId(4592), SourceName("voila"), Instant.EPOCH)
    assert(prices.size == 1, "the nameless entity is dropped, the good one survives")
    assert(prices.head.name.anyForm.contains("Good Milk 2 L"))
  }

  test("a non-CAD amount is refused rather than silently mixed") {
    val html = """<script>window.__INITIAL_STATE__ = {"data":{"products":{"productEntities":{
      "a":{"name":"Imported Thing","price":{"current":{"amount":"4.99","currency":"USD"}}}
    }}}};</script>"""
    val Right(prices) = VoilaSource.decodePage(html, MerchantId(4592), SourceName("voila"), Instant.EPOCH)
    assert(prices.head.regularPrice.isEmpty, "currencies must never silently mix (00.1)")
  }

  test("an end-to-end lookup maps the page through the transport") {
    val html = new String(java.nio.file.Files.readAllBytes(fixture("voila_search.sample.html")), StandardCharsets.UTF_8)
    val src  = new VoilaSource[IO](VoilaConfig(), getStub(200, html), policy)
    val Right(prices) = src.lookup("lait", postal, Locale.FrCa).unsafeRunSync()
    assert(prices.size == 4)
  }

  test("a transport failure stays a typed, retriable value") {
    val src    = new VoilaSource[IO](VoilaConfig(), getStub(503, "down"), policy)
    val result = src.lookup("lait", postal, Locale.FrCa).unsafeRunSync()
    assert(result == Left(DealWatchError.HttpStatus(503, VoilaSource.searchUrl("https://voila.ca", "lait"))))
    assert(result.swap.exists(_.retriable))
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
    val src    = new VoilaSource[IO](VoilaConfig(), getStub(503, "down"), policy)
    val result = src.lookup("lait", postal, Locale.FrCa).unsafeRunSync()
    assert(result.isLeft)
    // non-blocking by construction: the caller gets a value, not an exception
    assert(result.swap.exists(_.retriable))
  }
}
