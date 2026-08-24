package demeter.alerting

import scala.concurrent.duration._

import cats.effect.kernel.{Resource, Sync}
import cats.syntax.all._
import demeter.foundations.DealWatchError
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence
import org.eclipse.paho.client.mqttv3.{MqttClient, MqttConnectOptions, MqttException, MqttMessage}

/** Spec 05.4, the MQTT half — publish alerts to a topic Home Assistant
  * subscribes to, as the alternative to the webhook.
  *
  * This was previously stubbed to always fail, so configuring an MQTT topic
  * with no webhook produced a sink that could never deliver.
  */
final case class MqttConfig(
    brokerUrl: String,
    clientId: String = "demeter-service",
    username: Option[String] = None,
    password: Option[String] = None,
    /** QoS 1 (at least once): a duplicate alert is a nuisance, a lost deal is
      * the thing this service exists to prevent. Dedup (05.2) already absorbs
      * the duplicate case.
      */
    qos: Int = 1,
    /** Deliberately NOT retained. A retained alert is redelivered to every new
      * subscriber, so an HA restart would re-fire the automation and re-notify
      * about a deal from last week. Retention is right for a sensor's state,
      * wrong for an event stream.
      */
    retain: Boolean = false,
    connectionTimeout: FiniteDuration = 10.seconds,
    keepAlive: FiniteDuration = 60.seconds,
)

object MqttPublisher {

  /** Split out from the client so the wiring is testable without a broker. */
  def connectOptions(config: MqttConfig): MqttConnectOptions = {
    val opts = new MqttConnectOptions()
    opts.setCleanSession(true)
    opts.setAutomaticReconnect(true)
    opts.setConnectionTimeout(config.connectionTimeout.toSeconds.toInt)
    opts.setKeepAliveInterval(config.keepAlive.toSeconds.toInt)
    config.username.foreach(opts.setUserName)
    config.password.foreach(p => opts.setPassword(p.toCharArray))
    opts
  }

  def messageFor(payload: String, config: MqttConfig): MqttMessage = {
    val msg = new MqttMessage(payload.getBytes("UTF-8"))
    msg.setQos(config.qos)
    msg.setRetained(config.retain)
    msg
  }

  /** The connection is a Resource: MQTT holds a long-lived session, and the
    * broker should see a clean disconnect rather than a dropped socket.
    *
    * Yields the same `(topic, payload) => F[...]` shape HomeAssistantSink
    * already takes, so the sink itself needs no knowledge of MQTT.
    */
  def resource[F[_]](config: MqttConfig)(implicit
      F: Sync[F]
  ): Resource[F, (String, String) => F[Either[DealWatchError, Unit]]] =
    Resource
      .make(
        F.blocking {
          // MemoryPersistence rather than the file-based default: this publishes
          // a handful of small messages a day and has no business writing a
          // spool directory next to the working directory.
          val client = new MqttClient(config.brokerUrl, config.clientId, new MemoryPersistence)
          client.connect(connectOptions(config))
          client
        }
      )(client =>
        F.blocking {
          if (client.isConnected) client.disconnect()
          client.close()
        }.handleError(_ => ())
      )
      .map(client => publishWith(client, config))

  private def publishWith[F[_]](client: MqttClient, config: MqttConfig)(implicit
      F: Sync[F]
  ): (String, String) => F[Either[DealWatchError, Unit]] =
    (topic, payload) =>
      F.blocking(client.publish(topic, messageFor(payload, config)))
        .as(Right(()): Either[DealWatchError, Unit])
        .recover {
          // A broker outage is transient and worth retrying or falling back to
          // the next sink in the chain (05.5), so it maps to a retriable
          // transport error rather than something that kills the run.
          case e: MqttException =>
            Left(DealWatchError.Transport(config.brokerUrl, s"mqtt ${e.getReasonCode}: ${e.getMessage}"))
          case e => Left(DealWatchError.Transport(config.brokerUrl, e.toString))
        }
}
