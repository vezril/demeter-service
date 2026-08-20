package demeter.persistence

import java.time.Instant

import scala.concurrent.duration._

import demeter.foundations._
import org.scalatest.funsuite.AnyFunSuite

/** Spec 03.4 — the pure selection rule, testable without a database. */
final class FlyerLedgerRuleSpec extends AnyFunSuite {

  private val jul16 = Instant.parse("2026-07-16T00:00:00Z")
  private val jul23 = Instant.parse("2026-07-23T00:00:00Z")
  private val jul30 = Instant.parse("2026-07-30T00:00:00Z")
  private val now   = Instant.parse("2026-07-26T08:00:00Z")
  private val pc    = PostalCode.parse("H2X1Y6").toOption.get

  private def flyer(from: Instant, to: Instant): Flyer =
    Flyer.of(FlyerId(900), MerchantId(1), "Weekly", from, to, pc, Locale.EnCa).toOption.get

  private def entry(from: Instant, to: Instant, fetchedAt: Instant) =
    LedgerEntry(FlyerId(900), from, to, fetchedAt)

  test("a never-seen flyer is selected for fetching") {
    assert(FlyerLedger.needsFetch(flyer(jul23, jul30), None, now, 7.days))
  }

  test("a flyer already fetched for its current window is skipped") {
    val recorded = entry(jul23, jul30, now.minusSeconds(3600))
    assert(!FlyerLedger.needsFetch(flyer(jul23, jul30), Some(recorded), now, 7.days))
  }

  test("a re-issued flyer with a changed window is re-selected") {
    val recorded = entry(jul16, jul23, now.minusSeconds(3600))
    assert(FlyerLedger.needsFetch(flyer(jul23, jul30), Some(recorded), now, 7.days))
  }

  test("a stale fetch beyond max age is refreshed") {
    val current = flyer(jul23, jul30)
    val recent  = entry(jul23, jul30, now.minus(java.time.Duration.ofDays(2)))
    val stale   = entry(jul23, jul30, now.minus(java.time.Duration.ofDays(8)))
    assert(!FlyerLedger.needsFetch(current, Some(recent), now, 7.days))
    assert(FlyerLedger.needsFetch(current, Some(stale), now, 7.days))
  }
}
