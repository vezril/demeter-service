package demeter.persistence

import java.time.Instant
import java.time.temporal.ChronoUnit

import scala.concurrent.duration._

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import demeter.foundations._
import doobie.implicits._
import doobie.postgres.implicits._
import org.scalatest.funsuite.AnyFunSuite

/** Specs 03.1–03.4 @boundary — against the docker-compose Postgres.
  *
  * Every case runs through `pgTest`, which CANCELS (not fails) when the
  * container isn't up, so `sbt test` stays green on a machine without Docker:
  * `docker compose up -d postgres` to actually exercise them.
  */
final class StoresIntegrationSpec extends AnyFunSuite {

  /** Cancel rather than abort when Postgres is absent; reset state per test. */
  private def pgTest(name: String)(body: => Any): Unit =
    test(name) {
      assume(PgTest.available, "Postgres not reachable on localhost:55432 — run `docker compose up -d postgres`")
      PgTest.migrated
      PgTest.truncateAll()
      body
      ()
    }

  private val now   = Instant.parse("2026-07-26T12:00:00Z").truncatedTo(ChronoUnit.MICROS)
  private val jul16 = Instant.parse("2026-07-16T00:00:00Z")
  private val jul23 = Instant.parse("2026-07-23T00:00:00Z")
  private val jul30 = Instant.parse("2026-07-30T00:00:00Z")
  private val pc    = PostalCode.parse("H2X1Y6").toOption.get

  private def rawStore(dedup: Option[FiniteDuration] = None) = new DoobieRawResponseStore[IO](PgTest.xa, dedup)
  private def obsStore                                        = new DoobieObservationStore[IO](PgTest.xa)
  private def ledger                                          = new DoobieFlyerLedger[IO](PgTest.xa, maxAge = 7.days)

  private def raw(bytes: Array[Byte], at: Instant = now) =
    RawResponse(bytes, "application/json", at, "https://backflipp.wishabi.com/flipp/flyers")

  private def putRaw(bytes: Array[Byte] = """{"x":1}""".getBytes): RawResponseId =
    rawStore().put(raw(bytes), SourceName("flipp"), ResponseKind.FlyerItems, pc, Locale.EnCa).unsafeRunSync().toOption.get

  private def obs(
      key: String = "v1:k1",
      flyerId: Long = 900L,
      observedAt: Instant = now,
      cents: Option[Long] = Some(499L),
      basis: PriceBasis = PriceBasis.ScalarPrice,
      validFrom: Instant = jul23,
      validTo: Instant = jul30,
  ): PriceObservation =
    PriceObservation(
      productKey = ProductKey(key),
      merchantId = MerchantId(100),
      flyerId = FlyerId(flyerId),
      observedAt = observedAt,
      name = BilingualText(Some("Lait Natrel 4 L"), Some("Natrel Milk 4 L")),
      rawName = "Lait Natrel 4 L | Natrel Milk 4 L",
      effectivePrice = cents.map(Money.cents(_)),
      priceBasis = basis,
      originalPrice = None,
      size = Some(Size(BigDecimal(4), StdUnit.PerLitre, 1)),
      unitPrice = cents.map(c => UnitPrice(Money.cents(c / 4), StdUnit.PerLitre)),
      saleText = if (cents.isEmpty) Some("50% off") else None,
      validFrom = validFrom,
      validTo = validTo,
      priceConfidence = Confidence.High,
      matchConfidence = Confidence.High,
    )

  pgTest("migrate is idempotent — it runs on every boot, not just the first") {
    // an unguarded backfill referencing a column it later drops succeeds once
    // and fails forever after, so the service starts once and never again
    Schema.migrate(PgTest.xa).unsafeRunSync()
    Schema.migrate(PgTest.xa).unsafeRunSync()
    Schema.migrate(PgTest.xa).unsafeRunSync()

    val cols = sql"""SELECT column_name FROM information_schema.columns
                     WHERE table_name = 'price_observation'
                       AND column_name IN ('confidence','price_confidence','match_confidence')"""
      .query[String].to[List].transact(PgTest.xa).unsafeRunSync().sorted
    assert(cols == List("match_confidence", "price_confidence"), s"unexpected columns: $cols")
  }

  pgTest("both confidences survive the round trip independently") {
    val rawId = putRaw()
    val mixed = obs().copy(priceConfidence = Confidence.High, matchConfidence = Confidence.Low)
    obsStore.save(mixed, rawId).unsafeRunSync()

    val List(back) = obsStore.observationsFor(mixed.productKey, now.minusSeconds(60)).unsafeRunSync()
    assert(back.priceConfidence == Confidence.High, "a clean price stays trusted")
    assert(back.matchConfidence == Confidence.Low, "even when identity is not")
    assert(back.confidence == Confidence.Low, "and the derived combined view is the minimum")
  }

  // --- 03.2 raw response store ---

  pgTest("stored bytes are returned byte-for-byte") {
    val bytes = Array.range(0, 256).map(_.toByte) ++ "flyer 🍁 bytes".getBytes("UTF-8")
    val id    = rawStore().put(raw(bytes), SourceName("flipp"), ResponseKind.Flyers, pc, Locale.EnCa).unsafeRunSync().toOption.get
    val back  = rawStore().get(id).unsafeRunSync().toOption.get
    assert(back.bytes.sameElements(bytes))
    assert(back.url == "https://backflipp.wishabi.com/flipp/flyers")
  }

  pgTest("identical fetches can be deduplicated by content hash") {
    val store = rawStore(dedup = Some(2.hours))
    val bytes = """{"same":"body"}""".getBytes
    val id1 = store.put(raw(bytes, now), SourceName("flipp"), ResponseKind.Flyers, pc, Locale.EnCa).unsafeRunSync().toOption.get
    val id2 = store.put(raw(bytes, now.plusSeconds(3600)), SourceName("flipp"), ResponseKind.Flyers, pc, Locale.EnCa).unsafeRunSync().toOption.get
    assert(id1 == id2)
  }

  pgTest("dedup off by default: identical fetches create distinct rows") {
    val store = rawStore()
    val bytes = """{"same":"body"}""".getBytes
    val id1   = store.put(raw(bytes), SourceName("flipp"), ResponseKind.Flyers, pc, Locale.EnCa).unsafeRunSync().toOption.get
    val id2   = store.put(raw(bytes), SourceName("flipp"), ResponseKind.Flyers, pc, Locale.EnCa).unsafeRunSync().toOption.get
    assert(id1 != id2)
  }

  pgTest("replay streams every archived response for a source and kind") {
    val store = rawStore()
    (1 to 5).foreach(i => store.put(raw(s"""{"n":$i}""".getBytes), SourceName("flipp"), ResponseKind.FlyerItems, pc, Locale.EnCa).unsafeRunSync())
    store.put(raw("""{"other":1}""".getBytes), SourceName("flipp"), ResponseKind.Flyers, pc, Locale.EnCa).unsafeRunSync()
    val streamed = store.stream(SourceName("flipp"), ResponseKind.FlyerItems).compile.toList.unsafeRunSync()
    assert(streamed.size == 5)
  }

  // --- 03.3 observation store ---

  pgTest("saving the same observation twice inserts once") {
    val rawId = putRaw()
    val o     = obs()
    assert(obsStore.save(o, rawId).unsafeRunSync() == Right(SaveOutcome.Inserted))
    assert(obsStore.save(o, rawId).unsafeRunSync() == Right(SaveOutcome.SkippedDuplicate))
    val count = sql"SELECT count(*) FROM price_observation".query[Int].unique.transact(PgTest.xa).unsafeRunSync()
    assert(count == 1)
  }

  pgTest("a batch save reports per-item outcomes") {
    val rawId    = putRaw()
    val existing = (1 to 2).map(i => obs(key = s"v1:k$i")).toList
    existing.foreach(o => obsStore.save(o, rawId).unsafeRunSync())
    val batch          = (1 to 10).map(i => obs(key = s"v1:k$i")).toList
    val Right(report)  = obsStore.saveAll(batch, rawId).unsafeRunSync()
    assert(report == SaveReport(inserted = 8, skippedDuplicate = 2, failed = 0))
  }

  pgTest("saving upserts the product dimension") {
    val rawId = putRaw()
    obsStore.save(obs(), rawId).unsafeRunSync()
    val (en, qty) = sql"SELECT display_name_en, size_qty FROM product WHERE key = 'v1:k1'"
      .query[(Option[String], Option[BigDecimal])].unique.transact(PgTest.xa).unsafeRunSync()
    assert(en.contains("Natrel Milk 4 L"))
    assert(qty.contains(BigDecimal(4)))
  }

  pgTest("a null effective price is storable and queryable") {
    val rawId = putRaw()
    obsStore.save(obs(cents = None, basis = PriceBasis.PercentOffUnknown), rawId).unsafeRunSync()
    val promos = sql"""SELECT count(*) FROM price_observation WHERE effective_cents IS NULL AND price_basis = 'PercentOffUnknown'"""
      .query[Int].unique.transact(PgTest.xa).unsafeRunSync()
    assert(promos == 1)
  }

  pgTest("every observation is traceable to a raw response") {
    val rawId = putRaw("""{"trace":"me"}""".getBytes)
    obsStore.save(obs(), rawId).unsafeRunSync()
    val linked = sql"""SELECT r.body FROM price_observation o JOIN raw_response r ON r.id = o.raw_response_id"""
      .query[Array[Byte]].unique.transact(PgTest.xa).unsafeRunSync()
    assert(linked.sameElements("""{"trace":"me"}""".getBytes))
  }

  pgTest("history query returns observations for a key since a cutoff, newest first") {
    val rawId = putRaw()
    val weeks = List(now.minus(21, ChronoUnit.DAYS), now.minus(14, ChronoUnit.DAYS), now.minus(7, ChronoUnit.DAYS))
    weeks.zipWithIndex.foreach { case (t, i) =>
      obsStore.save(obs(observedAt = t, flyerId = 900L + i), rawId).unsafeRunSync()
    }
    val results = obsStore.observationsFor(ProductKey("v1:k1"), now.minus(15, ChronoUnit.DAYS)).unsafeRunSync()
    assert(results.size == 2)
    assert(results.map(_.observedAt) == results.map(_.observedAt).sorted.reverse)
  }

  pgTest("current observations return only those active at a given time") {
    val rawId = putRaw()
    obsStore.save(obs(key = "v1:active", validFrom = jul23, validTo = jul30), rawId).unsafeRunSync()
    obsStore.save(obs(key = "v1:expired", flyerId = 901L, validFrom = jul16, validTo = jul23.minusSeconds(1)), rawId).unsafeRunSync()
    val active = obsStore.currentObservationsFor(MerchantId(100), now).compile.toList.unsafeRunSync()
    assert(active.map(_.productKey.value) == List("v1:active"))
  }

  pgTest("observations round-trip through the store") {
    val rawId = putRaw()
    val o     = obs()
    obsStore.save(o, rawId).unsafeRunSync()
    val List(back) = obsStore.observationsFor(o.productKey, now.minusSeconds(60)).unsafeRunSync()
    assert(back == o)
  }

  // --- 03.4 flyer ledger ---

  private def flyer(id: Long, from: Instant, to: Instant): Flyer =
    Flyer.of(FlyerId(id), MerchantId(100), "Weekly", from, to, pc, Locale.EnCa).toOption.get

  pgTest("a never-seen flyer is selected; one fetched for its window is skipped; a changed window re-selects") {
    val rawId = putRaw()
    val f     = flyer(900, jul23, jul30)

    val first = ledger.selectToFetch(List(f), now).unsafeRunSync()
    assert(first == List(f))

    ledger.markFetched(f.id, (jul23, jul30), rawId).unsafeRunSync()
    assert(ledger.selectToFetch(List(f), now).unsafeRunSync().isEmpty)

    val reissued = flyer(900, jul30, jul30.plus(7, ChronoUnit.DAYS))
    assert(ledger.selectToFetch(List(reissued), now).unsafeRunSync() == List(reissued))
  }

  pgTest("seen timestamps update even when a flyer is skipped") {
    val rawId = putRaw()
    val f     = flyer(900, jul23, jul30)
    ledger.selectToFetch(List(f), now).unsafeRunSync()
    ledger.markFetched(f.id, (jul23, jul30), rawId).unsafeRunSync()

    val later = now.plus(1, ChronoUnit.DAYS)
    val selected = ledger.selectToFetch(List(f), later).unsafeRunSync()
    assert(selected.isEmpty) // skipped...
    assert(ledger.lastSeenAt(f.id).unsafeRunSync().contains(later)) // ...but still seen
  }

  pgTest("a stale fetch beyond max age is refreshed") {
    val rawId = putRaw()
    val f     = flyer(900, jul16, jul30.plus(30, ChronoUnit.DAYS))
    ledger.selectToFetch(List(f), now).unsafeRunSync()
    ledger.markFetched(f.id, (f.validFrom, f.validTo), rawId).unsafeRunSync()
    // age the recorded fetch by rewriting fetched_at
    sql"UPDATE flyer_fetch_ledger SET fetched_at = ${now.minus(8, ChronoUnit.DAYS)}".update.run
      .transact(PgTest.xa).unsafeRunSync()
    assert(ledger.selectToFetch(List(f), now).unsafeRunSync() == List(f))
  }

  // --- 03.1 rebuild-from-raws invariant (storage side; the full pipeline replay
  //     runs in orchestration's end-to-end suite) ---

  pgTest("history can be rebuilt from raw responses alone") {
    val store = rawStore()
    // archive raws whose bodies encode the observations (stub normalizer: parse cents from body)
    val bodies = List("""{"cents":250}""", """{"cents":299}""")
    bodies.foreach(b => store.put(raw(b.getBytes), SourceName("flipp"), ResponseKind.FlyerItems, pc, Locale.EnCa).unsafeRunSync())

    def normalize(bytes: Array[Byte]): PriceObservation = {
      val cents = """\d+""".r.findFirstIn(new String(bytes)).get.toLong
      obs(key = s"v1:rebuild$cents", cents = Some(cents))
    }

    def rebuild(): List[(String, Option[Long])] = {
      store
        .stream(SourceName("flipp"), ResponseKind.FlyerItems)
        .evalMap { case (id, r) => obsStore.save(normalize(r.bytes), id) }
        .compile.drain.unsafeRunSync()
      sql"SELECT product_key, effective_cents FROM price_observation ORDER BY product_key"
        .query[(String, Option[Long])].to[List].transact(PgTest.xa).unsafeRunSync()
    }

    val original = rebuild()
    sql"TRUNCATE price_observation".update.run.transact(PgTest.xa).unsafeRunSync()
    val rebuilt = rebuild()
    assert(rebuilt == original)
    assert(rebuilt.map(_._2) == List(Some(250L), Some(299L)))
  }
}
