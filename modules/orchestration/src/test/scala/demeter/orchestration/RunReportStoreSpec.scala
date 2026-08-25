package demeter.orchestration

import java.time.Instant

import scala.concurrent.duration._

import cats.effect.unsafe.implicits.global
import demeter.foundations.{DealWatchError, SourceName}
import org.scalatest.funsuite.AnyFunSuite

/** Spec 08.3 — the run report survives the process that produced it. Tags: @boundary. */
final class RunReportStoreSpec extends AnyFunSuite {

  private def pgTest(name: String)(body: DoobieRunReportStore[cats.effect.IO] => Any): Unit =
    test(name) {
      assume(PgTest.available, "Postgres not reachable on localhost:55432 — run `docker compose up -d postgres`")
      PgTest.migrated
      PgTest.truncateReports()
      body(new DoobieRunReportStore[cats.effect.IO](PgTest.xa))
      ()
    }

  private val started  = Instant.parse("2026-08-26T10:00:00Z")
  private val finished = Instant.parse("2026-08-26T10:39:05Z")

  private val full = RunReport(
    flyersListed = 156,
    flyersSelected = 156,
    flyersFetched = 153,
    flyersFailed = 3,
    itemsParsed = 21439,
    itemsDropped = 37,
    observationsInserted = 19538,
    observationsSkipped = 1901,
    matches = 272,
    alertsDelivered = 10,
    alertsSuppressed = 262,
    suppressedByReason = Map("above max price" -> 160, "not a sale" -> 89, "already alerted this window" -> 2),
    alertAudience = Some(1),
    degraded = List(DegradedSource(SourceName("pcexpress"), DealWatchError.HttpStatus(401, "x"))),
    failures = List(DealWatchError.Timeout("https://backflipp.wishabi.com/flipp/flyers")),
    elapsed = Some(2345.seconds),
  )

  pgTest("a report round-trips") { store =>
    store.save(full, started, finished).unsafeRunSync()
    val Some(back) = store.latest.unsafeRunSync()
    assert(back.startedAt == started)
    assert(back.finishedAt == finished)
    assert(back.report.matches == 272)
    assert(back.report.alertsDelivered == 10)
    assert(back.report.elapsed.contains(2345.seconds))
  }

  pgTest("suppression reasons survive as a map, not a total") { store =>
    // The whole point of persisting this. A bare count cannot distinguish a
    // price ceiling that is too tight from an empty history from having already
    // told you, and those want three different responses.
    store.save(full, started, finished).unsafeRunSync()
    val Some(back) = store.latest.unsafeRunSync()
    assert(back.report.suppressedByReason == full.suppressedByReason)
    assert(back.report.suppressedByReason("above max price") == 160)
  }

  pgTest("an unknown audience stays unknown, not zero") { store =>
    // A sink that cannot count its consumers is not a sink with none, and
    // storing NULL as 0 would raise a false no-audience alarm on replay.
    store.save(full.copy(alertAudience = None), started, finished).unsafeRunSync()
    val Some(back) = store.latest.unsafeRunSync()
    assert(back.report.alertAudience.isEmpty)
  }

  pgTest("a zero audience is preserved as zero") { store =>
    store.save(full.copy(alertAudience = Some(0)), started, finished).unsafeRunSync()
    val Some(back) = store.latest.unsafeRunSync()
    assert(back.report.alertAudience.contains(0))
  }

  pgTest("latest returns the most recent run, not the most recently inserted") { store =>
    val older = full.copy(matches = 1)
    val newer = full.copy(matches = 2)
    // Inserted out of order on purpose: ordering must come from finished_at.
    store.save(newer, started.plusSeconds(86400), finished.plusSeconds(86400)).unsafeRunSync()
    store.save(older, started, finished).unsafeRunSync()
    val Some(back) = store.latest.unsafeRunSync()
    assert(back.report.matches == 2)
  }

  pgTest("no runs yet is None, not an empty report") { store =>
    assert(store.latest.unsafeRunSync().isEmpty)
  }

  pgTest("a report with no suppressions and no elapsed stores cleanly") { store =>
    store.save(RunReport(flyersListed = 3), started, finished).unsafeRunSync()
    val Some(back) = store.latest.unsafeRunSync()
    assert(back.report.suppressedByReason.isEmpty)
    assert(back.report.elapsed.isEmpty)
  }
}
