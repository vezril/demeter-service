package demeter.ingestion

import demeter.foundations._
import io.circe.Json
import io.circe.parser.parse
import org.scalacheck.Gen
import org.scalatest.funsuite.AnyFunSuite
import org.scalatestplus.scalacheck.ScalaCheckPropertyChecks

/** Spec 01.5 — Flipp response decoders. Tags: @pure, @contract on the fixture cases. */
final class FlippDecodersSpec extends AnyFunSuite with ScalaCheckPropertyChecks {

  private def json(s: String): Json = parse(s).fold(e => fail(s"bad test JSON: $e"), identity)

  private val validItem = json(
    """{
      |  "flyer_item_id": 1027086069,
      |  "id": 1027086069,
      |  "flyer_id": 8035808,
      |  "merchant_id": 2269,
      |  "name": "LAIT FINEMENT FILTRÉ NATREL | NATREL FINE-FILTERED MILK",
      |  "current_price": 4.99,
      |  "original_price": null,
      |  "sale_story": "25 points",
      |  "valid_from": "2026-07-23T04:00:00+00:00",
      |  "valid_to": "2026-07-30T03:59:59+00:00"
      |}""".stripMargin
  )

  // --- price field (01.5 unit 1) ---

  test("a price field decodes across the shapes Flipp actually sends") {
    def price(v: Option[Json]) = FlippDecoders.priceValue("flipp", "p", v)
    assert(price(Some(json("4.99"))) == Right(Some(Money.cents(499))))
    assert(price(Some(json("\"4.99\""))) == Right(Some(Money.cents(499))))
    assert(price(Some(json("10"))) == Right(Some(Money.cents(1000))))
    assert(price(Some(Json.Null)) == Right(None))
    assert(price(None) == Right(None))
    assert(price(Some(json("0"))) == Right(Some(Money.cents(0))))
  }

  test("a non-numeric non-null price is a field-level decode error naming the pointer") {
    for (v <- Seq("\"N/A\"", "\"see store\"", "\"$4.99\"")) {
      val result = FlippDecoders.priceValue("flipp", "items[3].current_price", Some(json(v)))
      assert(result.isLeft, s"value: $v")
      assert(result.swap.exists(_.pointer == "items[3].current_price"))
    }
  }

  // --- instants (01.5 unit 2) ---

  test("offset datetimes parse to instants") {
    for (ts <- Seq("2026-07-23T04:00:00+00:00", "2026-07-30T03:59:59+00:00", "2026-07-23T04:00:00Z"))
      assert(FlippDecoders.instantValue("flipp", "t", json(s""""$ts"""")).isRight, s"ts: $ts")
  }

  test("a malformed timestamp is a decode error") {
    assert(FlippDecoders.instantValue("flipp", "t", json("\"not-a-date\"")).isLeft)
  }

  // --- item invariants ---

  test("a real captured item decodes to the expected FlyerItem (@contract)") {
    val parsed = FlippDecoders.parseJson("flipp", Fixtures.bytes("items_search.sample.json"))
    val Right(items) = parsed.flatMap(FlippDecoders.decodeItems("flipp", _))
    assert(items.dropped == 0)
    assert(items.items.size == 2)

    val first = items.items.head
    assert(first.rawName == "LAIT FINEMENT FILTRÉ NATREL | NATREL FINE-FILTERED MILK")
    assert(first.currentPrice.contains(Money.cents(499)))
    assert(first.originalPrice.isEmpty)
    assert(first.saleStory.contains("25 points"))
    assert(first.merchantId == MerchantId(2269))

    val second = items.items(1)
    assert(second.currentPrice.contains(Money.cents(699)))
    assert(second.originalPrice.contains(Money.cents(869)))
    assert(second.saleStory.isEmpty)
  }

  test("an item with validTo before validFrom is rejected") {
    val inverted = validItem.hcursor
      .downField("valid_from").set(json("\"2026-07-30T04:00:00+00:00\""))
      .top.get
    assert(FlippDecoders.decodeItem("flipp", inverted.hcursor, "items[0]").isLeft)
  }

  test("unknown extra fields do not break decoding (property)") {
    val extraKeys = Gen.mapOf(Gen.zip(Gen.identifier, Gen.oneOf(Json.True, Json.fromInt(7), Json.fromString("x"))))
    forAll(extraKeys) { extras =>
      val augmented = extras.foldLeft(validItem) { case (j, (k, v)) =>
        // never overwrite a real field — the property is about *additive* schema change
        if (j.hcursor.downField(k).succeeded) j else j.mapObject(_.add(k, v))
      }
      assert(FlippDecoders.decodeItem("flipp", augmented.hcursor, "items[0]").isRight)
    }
  }

  // --- listing (@contract, 01.2) ---

  test("a live-shaped flyers response decodes to the expected flyers (@contract)") {
    val Right(listing) =
      FlippDecoders.parseJson("flipp", Fixtures.bytes("flyers.sample.json")).flatMap(FlippDecoders.decodeListing("flipp", _))
    assert(listing.dropped == 0)
    assert(listing.flyers.size == 2)
    for (flyer <- listing.flyers) {
      assert(flyer.postalCode.canonical == "H2X1Y6")
      assert(flyer.validFrom.isBefore(flyer.validTo))
      assert(flyer.name.nonEmpty)
    }
    assert(listing.flyers.map(_.id) == List(FlyerId(8035808L), FlyerId(8041370L)))
    assert(listing.merchants.toSet == Set(Merchant(MerchantId(2269), "Metro"), Merchant(MerchantId(4592), "IGA")))
  }

  // --- search (@contract, 01.3) ---

  test("the two item arrays are decoded separately (@contract)") {
    val Right(search) =
      FlippDecoders.parseJson("flipp", Fixtures.bytes("items_search.sample.json")).flatMap(FlippDecoders.decodeSearch("flipp", _))
    assert(search.flyerItems.size == 2)
    assert(search.ecomItems.size == 1)
    assert(search.ecomItems.head.merchantName == "Walmart")
    assert(search.ecomItems.head.currentPrice.contains(Money.cents(788)))
    // no ecom item appears among the flyer items
    assert(search.flyerItems.forall(_.merchantId == MerchantId(2269)))
  }

  test("flyer items from search carry a usable merchant name") {
    val Right(search) =
      FlippDecoders.parseJson("flipp", Fixtures.bytes("items_search.sample.json")).flatMap(FlippDecoders.decodeSearch("flipp", _))
    assert(search.merchants.contains(Merchant(MerchantId(2269), "Metro")))
  }

  // --- the per-flyer shape (@contract, 01.4) ---
  //
  // This fixture did not exist until a live run: the repo shipped only the
  // SEARCH capture, and 01.4's field table described that shape too. Every
  // hand-written test below the old table therefore agreed with the decoder and
  // both were wrong about the endpoint they claimed to cover.

  test("a real per-flyer items response decodes — it has NO merchant and NO current_price (@contract)") {
    val Right(parsed) =
      FlippDecoders.parseJson("flipp", Fixtures.bytes("flyer_items.sample.json")).flatMap(FlippDecoders.decodeItems("flipp", _))

    assert(parsed.dropped == 0, "every item must decode; requiring merchant_id dropped all 170 in production")
    assert(parsed.items.size == 4)

    val ribs = parsed.items.find(_.rawName == "PORK BACK RIBS").get
    assert(ribs.currentPrice.contains(Money.cents(399)), "the price field is `price`, a string, not `current_price`")
    assert(ribs.flyerId == FlyerId(8092458L))
    assert(ribs.merchantId == FlippDecoders.UnresolvedMerchant, "merchant belongs to the flyer, resolved by the orchestrator")
    assert(ribs.validFrom.isBefore(ribs.validTo))
  }

  test("the undocumented `discount` integer is preserved opaquely, never read as a percentage") {
    val Right(parsed) =
      FlippDecoders.parseJson("flipp", Fixtures.bytes("flyer_items.sample.json")).flatMap(FlippDecoders.decodeItems("flipp", _))

    val ribs = parsed.items.find(_.rawName == "PORK BACK RIBS").get
    assert(ribs.saleStory.exists(_.contains("20")), "kept for diagnostics")
    assert(ribs.originalPrice.isEmpty, "no original price may be fabricated from an unverified unit")
    // and it must not be mistaken for a percent-off by 02.6 — there is no '%'
    assert(!ribs.saleStory.exists(_.contains("%")))

    val plain = parsed.items.find(_.rawName == "FRIED BREAD STICK").get
    assert(plain.saleStory.isEmpty, "an item with no discount carries no sale text")
  }

  test("both endpoint shapes decode through the same item decoder") {
    // search shape: merchant_id and current_price present
    val Right(search) =
      FlippDecoders.parseJson("flipp", Fixtures.bytes("items_search.sample.json")).flatMap(FlippDecoders.decodeSearch("flipp", _))
    assert(search.flyerItems.forall(_.merchantId != FlippDecoders.UnresolvedMerchant))

    // per-flyer shape: neither present
    val Right(perFlyer) =
      FlippDecoders.parseJson("flipp", Fixtures.bytes("flyer_items.sample.json")).flatMap(FlippDecoders.decodeItems("flipp", _))
    assert(perFlyer.items.forall(_.merchantId == FlippDecoders.UnresolvedMerchant))
    assert(perFlyer.items.forall(_.currentPrice.isDefined))
  }

  test("an explicit current_price still wins over the per-flyer `price` alias") {
    val json = FlippDecodersSpecHelpers.itemWithBoth
    val Right(item) = FlippDecoders.decodeItem("flipp", json.hcursor, "items[0]")
    assert(item.currentPrice.contains(Money.cents(499)), "current_price is the more specific field")
  }
}

private object FlippDecodersSpecHelpers {
  val itemWithBoth: io.circe.Json = io.circe.parser.parse(
    """{"id":1,"flyer_id":9,"name":"Both","current_price":4.99,"price":"1.11",
       "valid_from":"2026-08-20T04:00:00+00:00","valid_to":"2026-08-27T03:59:59+00:00"}"""
  ).toOption.get
}
