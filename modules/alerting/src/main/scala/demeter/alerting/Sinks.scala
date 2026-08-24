package demeter.alerting

import cats.effect.kernel.Concurrent
import cats.syntax.all._
import demeter.foundations.{DealWatchError, Locale}

/** Specs 05.4 / 05.5 — delivery.
  *
  * Every sink is opt-in and operator-configured; a delivery target is NEVER
  * derived from flyer content. The chain delivers to the first sink that
  * succeeds (you don't want the same alert on three channels), and a total
  * failure is surfaced as a run-health signal rather than swallowed.
  */
final case class SinkName(value: String) extends AnyVal

trait AlertSink[F[_]] {
  def name: SinkName
  def deliver(alert: Alert): F[Either[DealWatchError, Unit]]
}

final case class HaConfig(
    webhookUrl: Option[String] = None,
    mqttTopic: Option[String] = None,
    locale: Locale = Locale.EnCa,
)

/** Publishes the structured alert to Home Assistant via whichever mechanism is
  * configured: an HA webhook POST, or an MQTT topic HA subscribes to.
  */
final class HomeAssistantSink[F[_]](
    config: HaConfig,
    post: (String, String) => F[Either[DealWatchError, Unit]],
    publish: (String, String) => F[Either[DealWatchError, Unit]],
)(implicit F: Concurrent[F])
    extends AlertSink[F] {

  val name: SinkName = SinkName("home-assistant")

  def deliver(alert: Alert): F[Either[DealWatchError, Unit]] = {
    val body = alert.renderStructured.noSpaces
    (config.webhookUrl, config.mqttTopic) match {
      case (Some(url), _)   => post(url, body)
      case (None, Some(tp)) => publish(tp, body)
      case (None, None) =>
        F.pure(Left(DealWatchError.InvalidDomain("HomeAssistantSink", "no webhook URL or MQTT topic configured")))
    }
  }
}

/** ntfy: a topic URL the operator configures; the alert renders as plain text. */
final class NtfySink[F[_]](
    topicUrl: String,
    locale: Locale,
    post: (String, String) => F[Either[DealWatchError, Unit]],
) extends AlertSink[F] {
  val name: SinkName                                         = SinkName("ntfy")
  def deliver(alert: Alert): F[Either[DealWatchError, Unit]] = post(topicUrl, alert.renderPlain(locale))
}

/** Email via an operator-supplied send function (SMTP config lives in 08.4). */
final class EmailSink[F[_]](
    locale: Locale,
    send: (String, String) => F[Either[DealWatchError, Unit]],
) extends AlertSink[F] {
  val name: SinkName = SinkName("email")
  def deliver(alert: Alert): F[Either[DealWatchError, Unit]] =
    send(s"Deal: ${alert.watchLabel}", alert.renderPlain(locale))
}

/** Total failure across every sink — a run-health signal (08.3), never silence. */
final case class AllSinksFailed(attempted: List[SinkName], errors: List[DealWatchError])

final class ChainSink[F[_]](
    primary: AlertSink[F],
    fallbacks: List[AlertSink[F]],
)(implicit F: Concurrent[F])
    extends AlertSink[F] {

  val name: SinkName = SinkName("chain")

  def deliver(alert: Alert): F[Either[DealWatchError, Unit]] =
    deliverDetailed(alert).map {
      case Right(_) => Right(())
      case Left(failure) =>
        Left(
          DealWatchError.Transport(
            url = failure.attempted.map(_.value).mkString(","),
            cause = s"all sinks failed: ${failure.errors.mkString("; ")}",
          )
        )
    }

  /** Stops at the first success; reports which sinks were attempted on total failure. */
  def deliverDetailed(alert: Alert): F[Either[AllSinksFailed, SinkName]] =
    (primary :: fallbacks).foldLeft(F.pure(Left(AllSinksFailed(Nil, Nil)): Either[AllSinksFailed, SinkName])) {
      (acc, sink) =>
        acc.flatMap {
          case right @ Right(_) => F.pure(right)
          case Left(sofar) =>
            sink.deliver(alert).map {
              case Right(_) => Right(sink.name)
              case Left(e)  => Left(AllSinksFailed(sofar.attempted :+ sink.name, sofar.errors :+ e))
            }
        }
    }
}
