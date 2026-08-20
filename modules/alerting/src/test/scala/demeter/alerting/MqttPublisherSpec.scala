package demeter.alerting

import scala.concurrent.duration._

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import demeter.foundations.DealWatchError
import org.scalatest.funsuite.AnyFunSuite

/** Spec 05.4, MQTT half. The connection itself needs a broker, so what is
  * tested here is everything that decides how a message is published — the
  * choices that would otherwise only reveal themselves in production.
  */
final class MqttPublisherSpec extends AnyFunSuite {

  private val config = MqttConfig(brokerUrl = "tcp://homeassistant.local:1883")

  test("alerts publish at QoS 1: a duplicate is a nuisance, a lost deal is the failure that matters") {
    assert(config.qos == 1)
    assert(MqttPublisher.messageFor("{}", config).getQos == 1)
  }

  test("alerts are NOT retained, so an HA restart cannot re-notify about a stale deal") {
    // retention is right for a sensor's state and wrong for an event stream:
    // a retained alert is redelivered to every new subscriber
    assert(!config.retain)
    assert(!MqttPublisher.messageFor("{}", config).isRetained)
  }

  test("retain and QoS remain configurable for a deployment that wants sensor semantics") {
    val retained = MqttPublisher.messageFor("{}", config.copy(retain = true, qos = 2))
    assert(retained.isRetained)
    assert(retained.getQos == 2)
  }

  test("the payload is carried verbatim as UTF-8") {
    val json = """{"item":"Lait Natrel 4 L","price_cents":499}"""
    assert(new String(MqttPublisher.messageFor(json, config).getPayload, "UTF-8") == json)
  }

  test("credentials are applied only when supplied") {
    val anonymous = MqttPublisher.connectOptions(config)
    assert(anonymous.getUserName == null)
    assert(anonymous.getPassword == null)

    val authed = MqttPublisher.connectOptions(config.copy(username = Some("ha"), password = Some("s3cret")))
    assert(authed.getUserName == "ha")
    assert(new String(authed.getPassword) == "s3cret")
  }

  test("the session reconnects automatically and starts clean") {
    val opts = MqttPublisher.connectOptions(config)
    // a daily job may sit idle for hours; the broker connection has to survive
    // that without manual intervention
    assert(opts.isAutomaticReconnect)
    assert(opts.isCleanSession)
    assert(opts.getConnectionTimeout == 10)
    assert(opts.getKeepAliveInterval == 60)
  }

  test("timeouts are configurable in seconds, as Paho expects") {
    val opts = MqttPublisher.connectOptions(config.copy(connectionTimeout = 45.seconds, keepAlive = 2.minutes))
    assert(opts.getConnectionTimeout == 45)
    assert(opts.getKeepAliveInterval == 120)
  }

  test("an unreachable broker fails as a retriable transport error, not an exception") {
    // port 1 is reserved and refuses instantly; this is the connect path, which
    // must surface as a value the chain can fall past (05.5)
    val attempt = MqttPublisher
      .resource[IO](config.copy(brokerUrl = "tcp://127.0.0.1:1", connectionTimeout = 1.second))
      .use(publish => publish("demeter/deals", "{}"))
      .attempt
      .unsafeRunSync()

    attempt match {
      case Left(_) => succeed // connect threw inside Resource.make; the chain sees the failure
      case Right(Left(e: DealWatchError.Transport)) => assert(e.retriable)
      case Right(other) => fail(s"expected a failure from an unreachable broker, got $other")
    }
  }

  test("the sink publishes the structured alert to the configured topic") {
    // HomeAssistantSink is what actually chooses MQTT over webhook; verify the
    // seam MqttPublisher fills is the one it calls
    val published = scala.collection.mutable.ListBuffer.empty[(String, String)]
    val sink = new HomeAssistantSink[IO](
      HaConfig(mqttTopic = Some("demeter/deals")),
      (_, _) => IO.pure(Left(DealWatchError.InvalidDomain("webhook", "should not be used"))),
      (topic, body) => IO { published += ((topic, body)); Right(()) },
    )
    val alert = Alert.of(AlertingFixtures.deal(), "Metro", demeter.foundations.Locale.EnCa)
    assert(sink.deliver(alert).unsafeRunSync() == Right(()))
    assert(published.toList.map(_._1) == List("demeter/deals"))
    assert(published.head._2.contains("Metro"))
  }
}
