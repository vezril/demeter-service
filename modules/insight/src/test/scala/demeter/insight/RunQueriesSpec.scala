package demeter.insight

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import doobie.implicits._
import org.scalatest.funsuite.AnyFunSuite

/** Spec insight-api — reads over the demeter schema. Tags: @boundary. */
final class RunQueriesSpec extends AnyFunSuite {

  private def pgTest(name: String)(body: DoobieRunQueries[IO] => Any): Unit =
    test(name) {
      assume(PgTest.available, "Postgres not reachable on localhost:55432 — run `docker compose up -d postgres`")
      // The table is created by demeter's own migrations; this service only
      // reads, so the test creates it the way the schema does rather than
      // reaching for a store this module deliberately does not depend on.
      sql"""CREATE TABLE IF NOT EXISTS run_report (
              id                    bigserial PRIMARY KEY,
              started_at            timestamptz NOT NULL,
              finished_at           timestamptz NOT NULL,
              elapsed_seconds       bigint,
              flyers_listed         integer NOT NULL,
              flyers_selected       integer NOT NULL,
              flyers_fetched        integer NOT NULL,
              flyers_failed         integer NOT NULL,
              items_parsed          integer NOT NULL,
              items_dropped         integer NOT NULL,
              observations_inserted integer NOT NULL,
              observations_skipped  integer NOT NULL,
              matches               integer NOT NULL,
              alerts_delivered      integer NOT NULL,
              alerts_suppressed     integer NOT NULL,
              suppressed_by_reason  jsonb NOT NULL DEFAULT '{}'::jsonb,
              alert_audience        integer,
              degraded_sources      text[] NOT NULL DEFAULT '{}',
              failures              text[] NOT NULL DEFAULT '{}',
              partial               boolean NOT NULL DEFAULT false
            )""".update.run.transact(PgTest.xa).unsafeRunSync()
      sql"TRUNCATE run_report".update.run.transact(PgTest.xa).unsafeRunSync()
      body(new DoobieRunQueries[IO](PgTest.xa))
      ()
    }

  private def insert(
      matches: Int,
      finishedOffsetSeconds: Long,
      reasons: String = """{"above max price": 160, "not a sale": 89}""",
      audience: Option[Int] = Some(1),
  ): Unit =
    sql"""INSERT INTO run_report (
            started_at, finished_at, elapsed_seconds,
            flyers_listed, flyers_selected, flyers_fetched, flyers_failed,
            items_parsed, items_dropped, observations_inserted, observations_skipped,
            matches, alerts_delivered, alerts_suppressed,
            suppressed_by_reason, alert_audience, degraded_sources, failures, partial
          ) VALUES (
            now(), now() + make_interval(secs => $finishedOffsetSeconds), 2345,
            156, 156, 153, 3, 21439, 37, 19538, 1901,
            $matches, 10, 249, $reasons::jsonb, $audience,
            ARRAY['pcexpress'], ARRAY['Timeout(x)'], false
          )""".update.run.transact(PgTest.xa).void.unsafeRunSync()

  pgTest("the latest run is read back with its counts") { queries =>
    insert(matches = 272, finishedOffsetSeconds = 0)
    val Some(view) = queries.latest.unsafeRunSync()
    assert(view.flyers.listed == 156)
    assert(view.items.dropped == 37)
    assert(view.alerts.matched == 272)
    assert(view.degradedSources == List("pcexpress"))
  }

  pgTest("the jsonb suppression map comes back a map") { queries =>
    insert(matches = 272, finishedOffsetSeconds = 0)
    val Some(view) = queries.latest.unsafeRunSync()
    assert(view.alerts.suppressedByReason("above max price") == 160)
    assert(view.alerts.suppressedByReason("not a sale") == 89)
  }

  pgTest("a NULL audience stays unknown rather than becoming zero") { queries =>
    insert(matches = 1, finishedOffsetSeconds = 0, audience = None)
    val Some(view) = queries.latest.unsafeRunSync()
    assert(view.alerts.audience.isEmpty)
  }

  pgTest("latest orders by finished_at, not by insertion") { queries =>
    insert(matches = 2, finishedOffsetSeconds = 86400) // inserted first, finished later
    insert(matches = 1, finishedOffsetSeconds = 0)
    val Some(view) = queries.latest.unsafeRunSync()
    assert(view.alerts.matched == 2)
  }

  pgTest("no runs is None") { queries =>
    assert(queries.latest.unsafeRunSync().isEmpty)
  }

  pgTest("an unreadable suppression map does not hide the run's counts") { queries =>
    insert(matches = 272, finishedOffsetSeconds = 0, reasons = """{"malformed": "not a number"}""")
    val Some(view) = queries.latest.unsafeRunSync()
    assert(view.alerts.suppressedByReason.isEmpty)
    assert(view.alerts.matched == 272, "one bad column must not cost the whole response")
  }
}
