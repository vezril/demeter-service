package demeter.ingestion

import java.nio.charset.StandardCharsets

import scala.concurrent.duration._

import cats.effect.IO
import cats.effect.kernel.Ref
import cats.effect.std.Random
import cats.effect.unsafe.implicits.global
import demeter.foundations._
import org.scalatest.funsuite.AnyFunSuite

/** Specs 01.1–01.4 — the FlyerSource seam and the Flipp adapters, against stub
  * transports and captured fixtures. Tags: @pure (URLs), @boundary, @contract.
  */
final class FlippSourceSpec extends AnyFunSuite {

  private val postal = PostalCode.parse("H2X 1Y6").toOption.get
  private val fastConfig =
    HttpPolicyConfig(backoffBase = 1.milli, backoffCap = 2.millis, rateLimit = 1000, rateWindow = 1.second)

  private def source(transport: HttpTransport[IO], config: HttpPolicyConfig = fastConfig): FlippSource[IO] = {
    val policy = Random.scalaUtilRandom[IO].flatMap(implicit r => HttpPolicy.create[IO](config)).unsafeRunSync()
    new FlippSource[IO](transport, policy)
  }

  private def constant(status: Int, body: String): HttpTransport[IO] =
    (url, _) => IO.pure(Right(HttpResponse(status, body.getBytes(StandardCharsets.UTF_8), "application/json")))

  private def counting(status: Int, body: String): (Ref[IO, Int], HttpTransport[IO]) = {
    val counter = Ref.of[IO, Int](0).unsafeRunSync()
    val t: HttpTransport[IO] = (url, _) =>
      counter.update(_ + 1) *> IO.pure(
        Right(HttpResponse(status, body.getBytes(StandardCharsets.UTF_8), "application/json"))
      )
    (counter, t)
  }

  // --- URL building (@pure, 01.2/01.3) ---

  test("the flyers request URL is built from typed inputs") {
    assert(
      FlippUrls.flyers(FlippUrls.DefaultBase, postal, Locale.EnCa) ==
        "https://backflipp.wishabi.com/flipp/flyers?locale=en-ca&postal_code=H2X1Y6"
    )
    assert(
      FlippUrls.flyers(FlippUrls.DefaultBase, postal, Locale.FrCa) ==
        "https://backflipp.wishabi.com/flipp/flyers?locale=fr-ca&postal_code=H2X1Y6"
    )
  }

  test("the search URL encodes the term safely") {
    def q(term: String) = FlippUrls.search(FlippUrls.DefaultBase, term, postal, Locale.EnCa).split("&q=").last
    assert(q("milk") == "milk")
    assert(q("ground beef") == "ground%20beef")
    assert(q("café") == "caf%C3%A9")
  }

  // --- error mapping (@boundary, 01.2/01.6) ---

  test("a 503 maps to a retriable HttpStatus error after the retry budget") {
    val (counter, transport) = counting(503, "server error")
    val result               = source(transport).flyers(postal, Locale.EnCa).unsafeRunSync()
    assert(result == Left(DealWatchError.HttpStatus(503, FlippUrls.flyers(FlippUrls.DefaultBase, postal, Locale.EnCa))))
    assert(counter.get.unsafeRunSync() == fastConfig.maxAttempts)
  }

  test("a Cloudflare challenge maps to BotWall, not a generic status, with no retry") {
    val (counter, transport) = counting(403, "<html>cf-chl-bypass challenge</html>")
    val result               = source(transport).flyers(postal, Locale.EnCa).unsafeRunSync()
    result match {
      case Left(e: DealWatchError.BotWall) =>
        assert(!e.retriable)
        assert(e.operatorAttention)
      case other => fail(s"expected BotWall, got $other")
    }
    assert(counter.get.unsafeRunSync() == 1)
  }

  test("failures surface as typed values, not thrown exceptions") {
    val transport: HttpTransport[IO] = (url, _) => IO.pure(Left(DealWatchError.Transport(url, "connection reset")))
    val result                       = source(transport).flyers(postal, Locale.EnCa).unsafeRunSync()
    assert(result.left.exists(_.isInstanceOf[DealWatchError.Transport]))
  }

  // --- raw + parsed (@boundary/@contract, 01.1/01.2) ---

  test("a source hands back the raw bytes alongside the parsed flyers") {
    val body           = new String(Fixtures.bytes("flyers.sample.json"), StandardCharsets.UTF_8)
    val Right(listing) = source(constant(200, body)).flyers(postal, Locale.EnCa).unsafeRunSync()
    assert(listing.flyers.size == 2)
    assert(new String(listing.raw.bytes, StandardCharsets.UTF_8) == body)
    assert(listing.raw.url == FlippUrls.flyers(FlippUrls.DefaultBase, postal, Locale.EnCa))
  }

  // --- per-flyer items (@boundary, 01.4) ---

  private def itemJson(name: Option[String], price: String = "null", saleStory: String = "null"): String = {
    val nameField = name.fold("null")(n => s""""$n"""")
    s"""{
       |  "flyer_item_id": 1, "id": 1, "flyer_id": 900, "merchant_id": 100,
       |  "name": $nameField, "current_price": $price, "original_price": null,
       |  "sale_story": $saleStory,
       |  "valid_from": "2026-07-23T04:00:00+00:00", "valid_to": "2026-07-30T03:59:59+00:00"
       |}""".stripMargin
  }

  test("null current_price yields a price-absent item, not a failure") {
    val body         = s"""{"items":[${itemJson(Some("Widget"), price = "null", saleStory = "\"50% off\"")}]}"""
    val Right(items) = source(constant(200, body)).items(FlyerId(900), postal, Locale.EnCa).unsafeRunSync()
    assert(items.items.size == 1)
    assert(items.items.head.currentPrice.isEmpty)
    assert(items.items.head.saleStory.contains("50% off"))
    assert(items.dropped == 0)
  }

  test("a grocery flyer with scalar prices decodes cleanly") {
    val body         = s"""{"items":[${itemJson(Some("Milk"), price = "4.99")}]}"""
    val Right(items) = source(constant(200, body)).items(FlyerId(900), postal, Locale.EnCa).unsafeRunSync()
    assert(items.items.head.currentPrice.contains(Money.cents(499)))
  }

  test("individual malformed items are dropped and counted, not fatal") {
    val good         = (1 to 8).map(_ => itemJson(Some("Widget")))
    val bad          = (1 to 2).map(_ => itemJson(None))
    val body         = s"""{"items":[${(good ++ bad).mkString(",")}]}"""
    val Right(items) = source(constant(200, body)).items(FlyerId(900), postal, Locale.EnCa).unsafeRunSync()
    assert(items.items.size == 8)
    assert(items.dropped == 2)
  }

  test("a bilingual name is preserved raw for later splitting") {
    val raw          = "LAIT FINEMENT FILTRÉ NATREL | NATREL FINE-FILTERED MILK"
    val body         = s"""{"items":[${itemJson(Some(raw))}]}"""
    val Right(items) = source(constant(200, body)).items(FlyerId(900), postal, Locale.EnCa).unsafeRunSync()
    assert(items.items.head.rawName == raw)
    assert(items.items.head.name == BilingualText.empty) // split deferred to normalization
  }

  test("flyer image pages are not retained") {
    val body = s"""{"items":[${itemJson(Some("Widget"))}], "pages":[{"image_url":"http://f.wishabi.net/p.jpg"}]}"""
    val Right(items) = source(constant(200, body)).items(FlyerId(900), postal, Locale.EnCa).unsafeRunSync()
    assert(items.items.size == 1)
    assert(!new String(items.items.head.rawName).contains("jpg"))
  }

  // --- search (@boundary, 01.3) ---

  test("an empty search result set is a success, not an error") {
    val body          = """{"items":[], "ecom_items":[], "normalized_query":"unobtanium"}"""
    val Right(search) = source(constant(200, body)).search("unobtanium", postal, Locale.EnCa).unsafeRunSync()
    assert(search.flyerItems.isEmpty)
    assert(search.normalizedQuery.contains("unobtanium"))
  }

  // --- capabilities (@pure/@boundary, 01.1) ---

  test("a source advertises its capabilities honestly") {
    assert(
      source(constant(200, "{}")).capabilities ==
        Set(Capability.Flyers, Capability.Items, Capability.Search)
    )
  }

  test("calling an unsupported capability fails cleanly with no I/O") {
    val fixtures = new FixtureSource[IO](
      Fixtures.path("flyers.sample.json"),
      Fixtures.path("items_search.sample.json"),
      capabilities = Set(Capability.Flyers),
    )
    val result = fixtures.search("milk", postal, Locale.EnCa).unsafeRunSync()
    assert(result == Left(DealWatchError.Unsupported("fixture", "Search")))
    // and the supported capability still works
    assert(fixtures.flyers(postal, Locale.EnCa).unsafeRunSync().isRight)
  }
}
