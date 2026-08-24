package demeter.watchlist

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import demeter.foundations.{MerchantId, Money}
import doobie.implicits._
import org.scalatest.funsuite.AnyFunSuite

/** Persistence for the watchlist (04.1). Tags: @boundary — needs the
  * docker-compose Postgres; cancels cleanly without it.
  */
final class WatchStoreSpec extends AnyFunSuite {

  private def pgTest(name: String)(body: => Any): Unit =
    test(name) {
      assume(PgTest.available, "Postgres not reachable on localhost:55432 — run `docker compose up -d postgres`")
      PgTest.migrated
      PgTest.truncateWatches()
      body
      ()
    }

  private def store = new DoobieWatchStore[IO](PgTest.xa)

  private def watch(
      id: String = "w-milk",
      label: String = "Milk 4L",
      terms: List[String] = List("milk", "lait"),
      excludeTerms: List[String] = Nil,
      merchants: Set[MerchantId] = Set.empty,
      maxPrice: Option[Long] = None,
      requireSale: Boolean = false,
      minDiscountPct: Option[Int] = None,
      active: Boolean = true,
  ): WatchItem =
    WatchItem
      .of(
        WatchId(id),
        label,
        terms,
        excludeTerms,
        merchants,
        maxPrice.map(Money.cents(_)),
        requireSale,
        minDiscountPct,
        active,
      )
      .toOption
      .get

  pgTest("a watch round-trips through the store with every field intact") {
    val original = watch(
      merchants = Set(MerchantId(2269), MerchantId(4592)),
      maxPrice = Some(300L),
      requireSale = true,
      minDiscountPct = Some(20),
    )
    store.upsert(original).unsafeRunSync()

    val loaded = store.load.unsafeRunSync()
    assert(loaded.rejected.isEmpty)
    assert(loaded.items.size == 1)
    assert(loaded.items.head == original)
  }

  pgTest("exclusion terms round-trip, and an absent list is empty rather than null") {
    store.upsert(watch(excludeTerms = List("arachide", "peanut"))).unsafeRunSync()
    val loaded = store.load.unsafeRunSync().items.head
    assert(loaded.excludeTerms == List("arachide", "peanut"))

    store.upsert(watch(id = "plain")).unsafeRunSync()
    assert(store.load.unsafeRunSync().items.find(_.id.value == "plain").get.excludeTerms.isEmpty)
  }

  pgTest("a watch predating the column loads with no exclusions") {
    // rows written before exclude_terms existed take the column default
    sql"""INSERT INTO watch_item (id, label, terms) VALUES ('legacy', 'Legacy', ARRAY['milk'])""".update.run
      .transact(PgTest.xa)
      .unsafeRunSync()
    val loaded = store.load.unsafeRunSync()
    assert(loaded.rejected.isEmpty)
    assert(loaded.items.find(_.id.value == "legacy").get.excludeTerms.isEmpty)
  }

  pgTest("upsert replaces an existing watch rather than duplicating it") {
    store.upsert(watch(terms = List("milk"))).unsafeRunSync()
    store.upsert(watch(terms = List("milk", "lait", "2%"), maxPrice = Some(250L))).unsafeRunSync()

    val loaded = store.load.unsafeRunSync().items
    assert(loaded.size == 1)
    assert(loaded.head.terms.toList == List("milk", "lait", "2%"))
    assert(loaded.head.maxPrice.contains(Money.cents(250)))
  }

  pgTest("active returns only the watches the run should match on") {
    store.upsert(watch(id = "on-1")).unsafeRunSync()
    store.upsert(watch(id = "on-2")).unsafeRunSync()
    store.upsert(watch(id = "off", active = false)).unsafeRunSync()

    assert(store.active.unsafeRunSync().map(_.id.value).sorted == List("on-1", "on-2"))
    assert(store.load.unsafeRunSync().items.size == 3) // load still sees them all
  }

  pgTest("a watch can be paused and resumed without losing its tuning") {
    store.upsert(watch(maxPrice = Some(300L), minDiscountPct = Some(25))).unsafeRunSync()

    assert(store.setActive(WatchId("w-milk"), isActive = false).unsafeRunSync() == Right(true))
    assert(store.active.unsafeRunSync().isEmpty)

    assert(store.setActive(WatchId("w-milk"), isActive = true).unsafeRunSync() == Right(true))
    val resumed = store.active.unsafeRunSync().head
    assert(resumed.maxPrice.contains(Money.cents(300)))
    assert(resumed.minDiscountPct.contains(25))
  }

  pgTest("setActive and delete report whether they actually matched a row") {
    assert(store.setActive(WatchId("ghost"), isActive = false).unsafeRunSync() == Right(false))
    assert(store.delete(WatchId("ghost")).unsafeRunSync() == Right(false))

    store.upsert(watch()).unsafeRunSync()
    assert(store.delete(WatchId("w-milk")).unsafeRunSync() == Right(true))
    assert(store.load.unsafeRunSync().items.isEmpty)
  }

  pgTest("an empty merchant set round-trips as any-merchant, not as null") {
    store.upsert(watch(merchants = Set.empty)).unsafeRunSync()
    val loaded = store.load.unsafeRunSync().items.head
    assert(loaded.merchants.isEmpty)
    assert(loaded.inScope(MerchantId(123)), "an empty scope means any merchant (04.1)")
  }

  pgTest("the table refuses a watch the domain would also refuse") {
    // the CHECK constraints and WatchItem.of state the same invariants; a
    // hand-written INSERT must not be able to smuggle in an invalid watch
    def attempt(frag: doobie.Fragment): Either[Throwable, Int] =
      scala.util.Try(frag.update.run.transact(PgTest.xa).unsafeRunSync()).toEither

    assert(
      attempt(sql"INSERT INTO watch_item (id, label, terms) VALUES ('a', 'ok', ARRAY[]::text[])").isLeft,
      "no terms must be rejected",
    )
    assert(
      attempt(sql"INSERT INTO watch_item (id, label, terms) VALUES ('b', '   ', ARRAY['milk'])").isLeft,
      "a blank label must be rejected",
    )
    assert(
      attempt(sql"""INSERT INTO watch_item (id, label, terms, min_discount_pct)
                    VALUES ('c', 'ok', ARRAY['milk'], 0)""").isLeft,
      "a 0% discount floor must be rejected",
    )
    assert(
      attempt(sql"""INSERT INTO watch_item (id, label, terms, min_discount_pct)
                    VALUES ('d', 'ok', ARRAY['milk'], 101)""").isLeft,
      "a >100% discount floor must be rejected",
    )
    assert(
      attempt(sql"""INSERT INTO watch_item (id, label, terms, max_price_cents)
                    VALUES ('e', 'ok', ARRAY['milk'], -1)""").isLeft,
      "a negative max price must be rejected",
    )
    assert(
      attempt(sql"""INSERT INTO watch_item (id, label, terms, min_discount_pct)
                    VALUES ('f', 'ok', ARRAY['milk'], 20)""").isRight,
      "a valid watch must still insert",
    )
  }

  pgTest("a stored row the domain rejects is reported, never silently dropped or force-built") {
    store.upsert(watch(id = "good")).unsafeRunSync()

    // A genuine gap between the two validations, not a contrived one: the CHECK
    // can only count array elements, while WatchItem.of trims each term and
    // discards the blanks. Expressing "at least one non-blank term after
    // trimming" in a simple CHECK isn't practical, so this row is legal in the
    // table and illegal in the domain — exactly the case WatchLoad.rejected
    // exists for, and the shape any future tightening of the domain will take.
    sql"""INSERT INTO watch_item (id, label, terms)
          VALUES ('blank-terms', 'Blank', ARRAY['', '   '])""".update.run.transact(PgTest.xa).unsafeRunSync()

    val loaded = store.load.unsafeRunSync()
    assert(loaded.items.map(_.id.value) == List("good"), "the valid watch still loads")
    assert(loaded.rejected.map(_._1) == List("blank-terms"), "the invalid one is named, not dropped")
    assert(loaded.rejected.map(_._2) == List(WatchItem.InvalidWatch.NoTerms), "with the reason why")
    assert(loaded.hasRejects)
  }

  pgTest("a rejected row never reaches the matcher") {
    sql"""INSERT INTO watch_item (id, label, terms)
          VALUES ('blank-terms', 'Blank', ARRAY['', '   '])""".update.run.transact(PgTest.xa).unsafeRunSync()
    assert(store.active.unsafeRunSync().isEmpty, "active must not surface a watch the domain refuses")
  }
}
