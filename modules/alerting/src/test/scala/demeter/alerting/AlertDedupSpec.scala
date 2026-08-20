package demeter.alerting

import java.time.Instant

import org.scalatest.funsuite.AnyFunSuite
import AlertingFixtures._

/** Spec 05.2 — suppress repeat alerts, surface genuinely new ones. Tags: @pure. */
final class AlertDedupSpec extends AnyFunSuite {

  private val at        = Instant.parse("2026-07-24T09:00:00Z")
  private val nextFrom  = Instant.parse("2026-07-30T00:00:00Z")
  private val nextTo    = Instant.parse("2026-08-06T00:00:00Z")

  private def sent(d: Deal): Map[AlertKey, AlertRecord] = Map(AlertDedup.keyOf(d) -> AlertDedup.record(d, at))

  test("the same deal in the same window alerts only once") {
    val first = deal(o = obs(Some(250L)))
    assert(AlertDedup.isNew(first, Map.empty))
    assert(!AlertDedup.isNew(first, sent(first)))
  }

  test("the same product in a new flyer window alerts again") {
    val thisWeek = deal(o = obs(Some(250L)))
    val nextWeek = deal(o = obs(Some(250L), validFrom = nextFrom, validTo = nextTo))
    assert(AlertDedup.isNew(nextWeek, sent(thisWeek)))
  }

  test("a price drop within the same window re-alerts") {
    val alerted = deal(o = obs(Some(299L)))
    val dropped = deal(o = obs(Some(250L)))
    assert(AlertDedup.isNew(dropped, sent(alerted)))
  }

  test("a price rise within the window does not re-alert") {
    val alerted = deal(o = obs(Some(250L)))
    val risen   = deal(o = obs(Some(299L)))
    assert(!AlertDedup.isNew(risen, sent(alerted)))
  }

  test("an identical price within the window does not re-alert") {
    val alerted = deal(o = obs(Some(250L)))
    assert(!AlertDedup.isNew(deal(o = obs(Some(250L))), sent(alerted)))
  }

  test("a no-price promo alerts once and stays quiet after") {
    val promo = deal(o = obs(cents = None, saleText = Some("50% off")))
    assert(AlertDedup.isNew(promo, Map.empty))
    assert(!AlertDedup.isNew(promo, sent(promo)))
  }

  test("different watches on the same product each get their own alert") {
    val milkWatch  = deal(w = watch(id = "w1"))
    val otherWatch = deal(w = watch(id = "w2"))
    assert(AlertDedup.isNew(otherWatch, sent(milkWatch)))
  }

  test("the dedup key is watch + product + flyer window") {
    val d   = deal()
    val key = AlertDedup.keyOf(d)
    assert(key.watchId == d.watch.id)
    assert(key.productKey == d.observation.productKey)
    assert(key.windowFrom == from && key.windowTo == to)
  }
}
