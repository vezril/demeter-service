package demeter.alerting

import java.sql.DriverManager
import java.time.Instant
import java.time.temporal.ChronoUnit

import scala.util.Try

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import demeter.foundations.{Money, ProductKey}
import demeter.persistence.Schema
import demeter.watchlist.WatchId
import doobie.Transactor
import doobie.implicits._
import org.scalatest.funsuite.AnyFunSuite

/** Spec 05.2 backing store. Tags: @boundary — needs the docker-compose Postgres;
  * cancels cleanly without it.
  */
final class AlertLedgerSpec extends AnyFunSuite {

  private val url = "jdbc:postgresql://localhost:55432/demeter_test"

  private lazy val available: Boolean =
    Try { Class.forName("org.postgresql.Driver"); DriverManager.getConnection(url, "demeter", "demeter").close() }.isSuccess

  private lazy val xa: Transactor[IO] =
    Transactor.fromDriverManager[IO]("org.postgresql.Driver", url, "demeter", "demeter", None)

  private lazy val migrated: Unit = Schema.migrate(xa).unsafeRunSync()

  private def pgTest(name: String)(body: => Any): Unit =
    test(name) {
      assume(available, "Postgres not reachable on localhost:55432 — run `docker compose up -d postgres`")
      migrated
      sql"TRUNCATE alert_ledger".update.run.transact(xa).void.unsafeRunSync()
      body
      ()
    }

  private def ledger = new DoobieAlertLedger[IO](xa)

  private val windowFrom = Instant.parse("2026-07-23T00:00:00Z")
  private val windowTo   = Instant.parse("2026-07-30T00:00:00Z")
  private val at         = Instant.parse("2026-07-24T09:00:00Z").truncatedTo(ChronoUnit.MICROS)

  private def key(watch: String = "w-milk", product: String = "v1:k", to: Instant = windowTo) =
    AlertKey(WatchId(watch), ProductKey(product), windowFrom, to)

  private def rec(k: AlertKey = key(), cents: Option[Long] = Some(250L)) =
    AlertRecord(k, cents.map(Money.cents(_)), at)

  pgTest("a recorded alert round-trips") {
    ledger.record(rec()).unsafeRunSync()
    val loaded = ledger.openAt(windowFrom).unsafeRunSync()
    assert(loaded.size == 1)
    assert(loaded(key()) == rec())
  }

  pgTest("only windows still open are loaded — a closed window is news again (05.2)") {
    val closed = key(product = "v1:closed", to = Instant.parse("2026-07-10T00:00:00Z"))
    ledger.record(rec(closed)).unsafeRunSync()
    ledger.record(rec(key())).unsafeRunSync()

    val open = ledger.openAt(Instant.parse("2026-07-24T00:00:00Z")).unsafeRunSync()
    assert(open.keySet.map(_.productKey.value) == Set("v1:k"))
  }

  pgTest("re-recording the same key updates rather than duplicating, carrying the newer price") {
    ledger.record(rec(cents = Some(299L))).unsafeRunSync()
    ledger.record(rec(cents = Some(250L))).unsafeRunSync()

    val loaded = ledger.openAt(windowFrom).unsafeRunSync()
    assert(loaded.size == 1, "the dedup key is the primary key; there can only be one row")
    assert(loaded(key()).alertedPrice.contains(Money.cents(250)))
  }

  pgTest("a price-absent alert is storable, and stays distinguishable from a priced one") {
    ledger.record(rec(cents = None)).unsafeRunSync()
    val loaded = ledger.openAt(windowFrom).unsafeRunSync()
    assert(loaded(key()).alertedPrice.isEmpty)
  }

  pgTest("different watches and different products keep separate ledger entries") {
    ledger.record(rec(key(watch = "w-milk"))).unsafeRunSync()
    ledger.record(rec(key(watch = "w-other"))).unsafeRunSync()
    ledger.record(rec(key(product = "v1:other"))).unsafeRunSync()
    assert(ledger.openAt(windowFrom).unsafeRunSync().size == 3)
  }

  pgTest("pruning drops only windows that closed before the cutoff") {
    ledger.record(rec(key(product = "v1:old", to = Instant.parse("2026-06-01T00:00:00Z")))).unsafeRunSync()
    ledger.record(rec(key())).unsafeRunSync()

    val dropped = ledger.prune(Instant.parse("2026-07-01T00:00:00Z")).unsafeRunSync()
    assert(dropped == 1)
    assert(ledger.openAt(windowFrom).unsafeRunSync().keySet.map(_.productKey.value) == Set("v1:k"))
  }

  pgTest("the round trip preserves what AlertDedup actually reads") {
    // the ledger only earns its keep if isNew() behaves the same against a
    // rehydrated map as against the in-memory one
    ledger.record(rec(cents = Some(299L))).unsafeRunSync()
    val rehydrated = ledger.openAt(windowFrom).unsafeRunSync()

    val cheaper = AlertRecord(key(), Some(Money.cents(250)), at)
    assert(rehydrated.get(key()).exists(_.alertedPrice.exists(_.cents > cheaper.alertedPrice.get.cents)),
           "a drop must remain detectable after a restart")
  }
}
