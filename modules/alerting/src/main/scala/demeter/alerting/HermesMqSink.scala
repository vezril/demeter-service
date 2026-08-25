package demeter.alerting

import java.security.MessageDigest
import java.nio.charset.StandardCharsets

import cats.Functor
import cats.syntax.all._
import io.circe.Json
import io.circe.parser.parse
import io.circe.syntax._
import demeter.foundations.DealWatchError

/** Spec 05.4 — delivery to HermesMQ, a single-node event-sourced broker.
  *
  * HermesMQ is not Home Assistant and not MQTT despite the name: it speaks a
  * REST API on its HTTP port, and its publish endpoint takes an envelope rather
  * than a bare body. Pointing the generic webhook sink at it POSTs the alert
  * JSON as the whole body and earns a 400, which is why this is its own sink
  * rather than a URL in the existing one.
  *
  *   POST {base}/v1/topics/{topic}/messages
  *   {"payload":"…","attributes":{…},"idempotencyKey":"…"}  ->  202 {messageId}
  */
final case class HermesMqConfig(
    baseUrl: String,
    topic: String,
    /** Sent as a bearer token when HERMESMQ_AUTH_ENABLED is on at the broker.
      * Wired now rather than retrofitted, so turning auth on is a config change
      * on both sides and not a code change on this one.
      */
    apiKey: Option[String] = None,
)

final class HermesMqSink[F[_]](
    config: HermesMqConfig,
    /** (url, jsonBody, bearerToken) => delivered. The bearer is separate from
      * the body so the token never reaches a log line that dumps the payload.
      */
    postJson: (String, String, Option[String]) => F[Either[DealWatchError, Unit]],
    /** (url, bearerToken) => body. Used to count subscribers; a broker that
      * cannot be asked has an unknown audience, never an empty one.
      */
    getJson: (String, Option[String]) => F[Either[DealWatchError, String]],
)(implicit F: Functor[F])
    extends AlertSink[F] {

  val name: SinkName = SinkName("hermesmq")

  /** Publishing into a topic nobody subscribes to is silent by construction:
    * the broker accepts every message and no one ever reads one. That is
    * indistinguishable from working, which is what 08.3 exists to catch.
    */
  def audience: F[Option[Int]] =
    getJson(HermesMqSink.subscriptionsUrl(config), config.apiKey).map {
      case Right(body) => HermesMqSink.countSubscribers(body, config.topic)
      case Left(_)     => None
    }

  def deliver(alert: Alert): F[Either[DealWatchError, Unit]] =
    postJson(HermesMqSink.publishUrl(config), HermesMqSink.envelope(alert).noSpaces, config.apiKey)
}

object HermesMqSink {

  def subscriptionsUrl(config: HermesMqConfig): String =
    s"${config.baseUrl.stripSuffix("/")}/v1/subscriptions"

  /** Counts the subscriptions bound to one topic. Returns None when the
    * response cannot be read at all -- an unreadable answer means the audience
    * is unknown, and reporting that as zero would raise a false alarm.
    */
  def countSubscribers(body: String, topic: String): Option[Int] =
    parse(body).toOption
      .flatMap(_.asArray)
      .map(_.count(_.hcursor.get[String]("topicId").contains(topic)))

  def publishUrl(config: HermesMqConfig): String =
    s"${config.baseUrl.stripSuffix("/")}/v1/topics/${config.topic}/messages"

  /** HermesMQ's `payload` is a string, not arbitrary JSON, so the structured
    * alert travels as an encoded string and the fields a consumer routes on are
    * lifted into `attributes` where they can be read without parsing it.
    */
  def envelope(alert: Alert): Json =
    Json.obj(
      "payload" -> alert.renderStructured.noSpaces.asJson,
      "attributes" -> Json.obj(
        "watch"    -> alert.watchLabel.asJson,
        "merchant" -> alert.merchantName.asJson,
        "verdict"  -> alert.verdict.phrase.asJson,
      ),
      "idempotencyKey" -> idempotencyKey(alert).asJson,
    )

  /** Derived from the payload itself, which gives exactly the semantics 05.2
    * already has: a retry after a restart publishes the same bytes and HermesMQ
    * drops the duplicate, while a price DROP changes price_cents, changes the
    * key, and is published as the fresh news it is.
    *
    * This is a safety net under the alert ledger, not a replacement for it. The
    * ledger decides whether to alert at all; this only stops one decision
    * becoming two messages.
    */
  def idempotencyKey(alert: Alert): String = {
    val bytes = MessageDigest
      .getInstance("SHA-256")
      .digest(alert.renderStructured.noSpaces.getBytes(StandardCharsets.UTF_8))
    bytes.take(16).map(b => f"${b & 0xff}%02x").mkString
  }
}
