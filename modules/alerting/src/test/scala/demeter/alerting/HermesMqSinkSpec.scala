package demeter.alerting

import java.time.Instant

import cats.Id
import demeter.foundations.{DealWatchError, Money}
import demeter.pricehistory.DealVerdict
import io.circe.parser.parse
import org.scalatest.funsuite.AnyFunSuite

/** Spec 05.4 — delivery to HermesMQ. Tags: @pure. */
final class HermesMqSinkSpec extends AnyFunSuite {

  private val config = HermesMqConfig("http://hermes:8080", "demeter-deals")

  private def alert(price: Long = 499L, item: String = "Beurre Lactantia 454g") =
    Alert(
      watchLabel = "Butter",
      merchantName = "IGA",
      itemName = item,
      price = Some(Money.cents(price)),
      saleText = Some("2/$8"),
      verdict = DealVerdict.BestEver(8),
      validTo = Instant.parse("2026-09-01T00:00:00Z"),
      score = 0.82,
    )

  /** Captures what would have been sent, so the shape is asserted rather than mocked away. */
  private def capturing(): (collection.mutable.ListBuffer[(String, String, Option[String])], HermesMqSink[Id]) = {
    val sent = collection.mutable.ListBuffer.empty[(String, String, Option[String])]
    val sink = new HermesMqSink[Id](config, (u, b, t) => { sent += ((u, b, t)); Right(()) })
    (sent, sink)
  }

  test("the publish URL is HermesMQ's topic endpoint, not the base URL") {
    assert(HermesMqSink.publishUrl(config) == "http://hermes:8080/v1/topics/demeter-deals/messages")
  }

  test("a trailing slash on the base URL does not produce a doubled path separator") {
    val trailing = HermesMqSink.publishUrl(config.copy(baseUrl = "http://hermes:8080/"))
    assert(trailing == "http://hermes:8080/v1/topics/demeter-deals/messages")
    assert(!trailing.contains("//v1"))
  }

  test("the alert is wrapped in HermesMQ's envelope, not posted as the bare body") {
    // Posting renderStructured directly is what the generic webhook sink does,
    // and it is exactly what HermesMQ rejects with a 400.
    val (sent, sink) = capturing()
    sink.deliver(alert())
    val Right(json) = parse(sent.head._2)
    val obj         = json.hcursor
    assert(obj.get[String]("payload").isRight, "payload is a string, per the API")
    assert(obj.downField("attributes").succeeded)
    assert(obj.get[String]("idempotencyKey").isRight)
  }

  test("the payload carries the structured alert, so nothing is lost in transit") {
    val (sent, sink) = capturing()
    sink.deliver(alert())
    val Right(env)     = parse(sent.head._2)
    val Right(payload) = env.hcursor.get[String]("payload")
    val Right(inner)   = parse(payload)
    assert(inner.hcursor.get[String]("item").contains("Beurre Lactantia 454g"))
    assert(inner.hcursor.get[Long]("price_cents").contains(499L))
  }

  test("routing fields are lifted into attributes so a consumer need not parse the payload") {
    val (sent, sink) = capturing()
    sink.deliver(alert())
    val Right(env) = parse(sent.head._2)
    val attrs      = env.hcursor.downField("attributes")
    assert(attrs.get[String]("watch").contains("Butter"))
    assert(attrs.get[String]("merchant").contains("IGA"))
  }

  test("the same alert publishes the same idempotency key, so a retry cannot double-send") {
    assert(HermesMqSink.idempotencyKey(alert()) == HermesMqSink.idempotencyKey(alert()))
  }

  test("a price drop changes the key, because a better deal is news again (05.2)") {
    // The alert ledger decides whether to alert at all; this only stops one
    // decision becoming two messages. A drop must NOT be deduplicated away.
    assert(HermesMqSink.idempotencyKey(alert(price = 499L)) != HermesMqSink.idempotencyKey(alert(price = 399L)))
  }

  test("a different item is a different message even at the same price") {
    assert(HermesMqSink.idempotencyKey(alert(item = "A")) != HermesMqSink.idempotencyKey(alert(item = "B")))
  }

  test("no api key means no Authorization header at all") {
    val (sent, sink) = capturing()
    sink.deliver(alert())
    assert(sent.head._3.isEmpty)
  }

  test("an api key is passed out of band, never inside the published body") {
    val sent = collection.mutable.ListBuffer.empty[(String, String, Option[String])]
    val sink =
      new HermesMqSink[Id](config.copy(apiKey = Some("s3cret")), (u, b, t) => { sent += ((u, b, t)); Right(()) })
    sink.deliver(alert())
    assert(sent.head._3.contains("s3cret"), "the token reaches the transport")
    assert(!sent.head._2.contains("s3cret"), "but never the payload, which gets logged")
  }

  test("a transport failure is returned as a value, so one dead sink cannot sink the run") {
    val boom = DealWatchError.Transport("http://hermes:8080", "connection refused")
    val sink = new HermesMqSink[Id](config, (_, _, _) => Left(boom))
    assert(sink.deliver(alert()) == Left(boom))
  }

  test("the sink names itself, so a run report can say which channel delivered") {
    assert(new HermesMqSink[Id](config, (_, _, _) => Right(())).name == SinkName("hermesmq"))
  }
}
