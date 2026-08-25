package demeter.orchestration

import java.time.Duration

import scala.concurrent.duration._

import demeter.foundations._
import demeter.ingestion.HttpPolicyConfig
import demeter.pricehistory.DealThresholds
import demeter.watchlist.ScoringWeights

/** Spec 08.4 — every operational knob in one validated place.
  *
  * Loaded once at startup and fully validated: a misconfiguration stops the
  * service at boot with a clear message rather than surfacing three layers deep
  * at 3 a.m. Secrets come from the environment / a secret file, are never
  * committed, and are redacted in any dump.
  */
final case class Secret(value: String) {
  override def toString: String = "***REDACTED***"
}

final case class SourceConfig(
    flippBaseUrl: String = "https://backflipp.wishabi.com/flipp",
    flippEnabled: Boolean = true,
    apifyFallbackEnabled: Boolean = false,
    apifyToken: Option[Secret] = None,
)

final case class EnrichmentConfig(
    pcExpressEnabled: Boolean = false,
    pcExpressApiKey: Option[Secret] = None,
    voilaEnabled: Boolean = false,
    canadianTireEnabled: Boolean = false,
)

final case class HistoryConfig(
    window: Duration = Duration.ofDays(8 * 7),
    minHistoryN: Int = 4,
)

final case class SinkConfig(
    haWebhookUrl: Option[String] = None,
    haMqttTopic: Option[String] = None,
    mqttBrokerUrl: Option[String] = None,
    mqttUsername: Option[String] = None,
    mqttPassword: Option[Secret] = None,
    ntfyTopicUrl: Option[String] = None,
    emailTo: Option[String] = None,
    /** HermesMQ speaks its own REST envelope, so it is a sink in its own right
      * rather than a webhook URL (see HermesMqSink).
      */
    hermesBaseUrl: Option[String] = None,
    hermesTopic: Option[String] = None,
    hermesApiKey: Option[Secret] = None,
    order: List[String] = List("hermesmq", "home-assistant", "ntfy", "email"),
)

final case class ScheduleConfig(
    cron: String = "0 6 * * *",
    // "06:00" is a local, wall-clock concern — as is a flyer's validity window —
    // so the zone is explicit rather than inherited from whatever the host is set to.
    zone: java.time.ZoneId = java.time.ZoneId.of("America/Montreal"),
    runOnStart: Boolean = false,
)

final case class StorageConfig(
    jdbcUrl: String = "jdbc:postgresql://localhost:5432/demeter",
    user: String = "demeter",
    password: Secret = Secret(""),
)

final case class RunConfig(
    flyerConcurrency: Int = 3,
    enrichmentEnabled: Boolean = false,
    flyerMaxAge: FiniteDuration = 7.days,
)

final case class Config(
    postalCode: PostalCode,
    locale: Locale,
    sources: SourceConfig = SourceConfig(),
    enrichment: EnrichmentConfig = EnrichmentConfig(),
    http: HttpPolicyConfig = HttpPolicyConfig(),
    scoring: ScoringWeights = ScoringWeights(),
    deals: DealThresholds = DealThresholds(),
    history: HistoryConfig = HistoryConfig(),
    sinks: SinkConfig = SinkConfig(),
    schedule: ScheduleConfig = ScheduleConfig(),
    storage: StorageConfig = StorageConfig(),
    run: RunConfig = RunConfig(),
) {

  /** Diagnostics-safe rendering: every secret is redacted (08.4). */
  def redactedDump: String =
    List(
      s"postalCode=${postalCode.canonical}",
      s"locale=${locale.queryValue}",
      s"sources.flippBaseUrl=${sources.flippBaseUrl}",
      s"sources.apifyToken=${sources.apifyToken.fold("unset")(_.toString)}",
      s"enrichment.pcExpressEnabled=${enrichment.pcExpressEnabled}",
      s"enrichment.pcExpressApiKey=${enrichment.pcExpressApiKey.fold("unset")(_.toString)}",
      s"http.maxAttempts=${http.maxAttempts}",
      s"history.windowDays=${history.window.toDays}",
      s"sinks.order=${sinks.order.mkString(",")}",
      s"sinks.hermes=${sinks.hermesBaseUrl.fold("unset")(b => s"$b -> ${sinks.hermesTopic.getOrElse("?")}")}",
      s"sinks.hermesApiKey=${sinks.hermesApiKey.fold("unset")(_ => "***REDACTED***")}",
      s"schedule=${DailySchedule.parse(schedule.cron, schedule.zone).map(_.describe).getOrElse(schedule.cron)}",
      s"storage.jdbcUrl=${storage.jdbcUrl}",
      s"storage.password=${storage.password.toString}",
      s"run.flyerConcurrency=${run.flyerConcurrency}",
    ).mkString("\n")
}

sealed abstract class ConfigError(val message: String) extends Product with Serializable

object ConfigError {
  final case class BadPostalCode(input: String, why: String) extends ConfigError(s"invalid postal code '$input': $why")
  final case class MissingKey(source: String, key: String)
      extends ConfigError(s"$source is enabled but $key is not set")
  case object EmptySinkChain extends ConfigError("the alert sink chain is empty — alerts would have nowhere to go")
  final case class BadValue(field: String, why: String)   extends ConfigError(s"invalid $field: $why")
  final case class BadSchedule(cron: String, why: String) extends ConfigError(s"invalid schedule.cron '$cron': $why")
}

object Config {

  /** Fail-fast validation. Every problem is reported, not just the first, so a
    * misconfigured deployment is fixed in one pass.
    */
  def validate(config: Config): Either[List[ConfigError], Config] = {
    val enrichmentErrors =
      if (config.enrichment.pcExpressEnabled && config.enrichment.pcExpressApiKey.forall(_.value.trim.isEmpty))
        List(ConfigError.MissingKey("pcexpress", "enrichment.pcExpressApiKey"))
      else Nil

    val fallbackErrors =
      if (config.sources.apifyFallbackEnabled && config.sources.apifyToken.forall(_.value.trim.isEmpty))
        List(ConfigError.MissingKey("apify-fallback", "sources.apifyToken"))
      else Nil

    val sinkErrors = {
      val configured = List(
        config.sinks.hermesBaseUrl.map(_ => "hermesmq"),
        config.sinks.haWebhookUrl.orElse(config.sinks.haMqttTopic).map(_ => "home-assistant"),
        config.sinks.ntfyTopicUrl.map(_ => "ntfy"),
        config.sinks.emailTo.map(_ => "email"),
      ).flatten
      if (configured.isEmpty || config.sinks.order.isEmpty) List(ConfigError.EmptySinkChain) else Nil
    }

    // An MQTT topic with nowhere to publish it is the same class of mistake as
    // an empty sink chain: it looks configured and delivers nothing.
    val mqttErrors =
      if (config.sinks.haMqttTopic.isDefined && config.sinks.mqttBrokerUrl.forall(_.trim.isEmpty))
        List(ConfigError.MissingKey("mqtt", "sinks.mqttBrokerUrl"))
      else Nil

    // A broker URL with no topic publishes nowhere, and a topic with no broker
    // has nowhere to publish to. Both look configured (05.4).
    val hermesErrors =
      (config.sinks.hermesBaseUrl, config.sinks.hermesTopic) match {
        case (Some(_), None) => List(ConfigError.MissingKey("hermesmq", "sinks.hermesTopic"))
        case (None, Some(_)) => List(ConfigError.MissingKey("hermesmq", "sinks.hermesBaseUrl"))
        case _               => Nil
      }

    val valueErrors =
      List(
        Option.when(config.run.flyerConcurrency < 1)(
          ConfigError.BadValue("run.flyerConcurrency", "must be at least 1")
        ),
        Option.when(config.http.maxAttempts < 1)(ConfigError.BadValue("http.maxAttempts", "must be at least 1")),
        Option.when(config.history.window.isZero || config.history.window.isNegative)(
          ConfigError.BadValue("history.window", "must be positive")
        ),
      ).flatten

    // The schedule is parsed at boot, not at first fire: an unrunnable cron
    // must stop startup rather than surface after the service has sat idle
    // looking healthy for a day (08.4).
    val scheduleErrors =
      DailySchedule
        .parse(config.schedule.cron, config.schedule.zone)
        .left
        .toSeq
        .map(why => ConfigError.BadSchedule(config.schedule.cron, why))
        .toList

    val errors =
      enrichmentErrors ++ fallbackErrors ++ sinkErrors ++ mqttErrors ++ hermesErrors ++ valueErrors ++ scheduleErrors
    if (errors.isEmpty) Right(config) else Left(errors)
  }

  /** Parses the location fields that come in as raw strings (env/HOCON). */
  def parsePostal(raw: String): Either[ConfigError, PostalCode] =
    PostalCode.parse(raw).left.map(e => ConfigError.BadPostalCode(raw, e.toString))
}
