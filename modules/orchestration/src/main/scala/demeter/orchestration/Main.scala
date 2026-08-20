package demeter.orchestration

import scala.concurrent.duration._

import cats.effect.{ExitCode, IO, IOApp, Resource}
import cats.effect.std.Random
import cats.syntax.all._
import demeter.alerting._
import demeter.foundations._
import demeter.ingestion._
import demeter.persistence._
import demeter.watchlist.WatchItem
import doobie.Transactor
import doobie.hikari.HikariTransactor
import org.http4s.ember.client.EmberClientBuilder
import org.typelevel.log4cats.slf4j.Slf4jLogger

/** Spec 08.1 / 08.4 — the entry point: load and validate config (fail fast),
  * wire the components, migrate the schema, then run on the configured
  * schedule. Nothing here makes policy decisions; it only assembles.
  */
object Main extends IOApp {

  def run(args: List[String]): IO[ExitCode] =
    Slf4jLogger.create[IO].flatMap { log =>
      loadConfig.flatMap {
        case Left(errors) =>
          // A misconfiguration stops the service at boot with a clear message,
          // rather than surfacing three layers deep at 3 a.m. (08.4)
          errors.traverse_(e => log.error(s"config error: ${e.message}")).as(ExitCode.Error)

        case Right(config) =>
          log.info(s"starting demeter-service\n${config.redactedDump}") *>
            components(config).use { case (source, rawStore, observations, ledger, sink) =>
              for {
                watchlist <- loadWatchlist
                run       <- DailyRun.create[IO](source, None, rawStore, observations, ledger, sink, config, watchlist)
                _         <- IO.whenA(config.schedule.runOnStart)(executeOnce(run, log).void)
                _         <- loop(run, log)
              } yield ExitCode.Success
            }
      }
    }

  private def executeOnce(run: DailyRun[IO], log: org.typelevel.log4cats.Logger[IO]): IO[RunReport] =
    run.run.flatTap { report =>
      val alarms = Observability.alarms(report, SourceName("flipp"))
      log.info(Observability.prometheus(report)) *>
        report.degraded.traverse_(d => log.warn(s"degraded: ${d.source.value} — ${d.reason}")) *>
        alarms.traverse_(a => log.warn(s"DRIFT ALARM: ${a.message}"))
    }

  /** v1 scheduling is a simple fixed interval; the cron string in config is the
    * hook for a real scheduler without touching the run itself.
    */
  private def loop(run: DailyRun[IO], log: org.typelevel.log4cats.Logger[IO]): IO[Unit] =
    (IO.sleep(24.hours) *> executeOnce(run, log).attempt.flatMap {
      case Left(e)  => log.error(s"run failed: $e")
      case Right(_) => IO.unit
    }).foreverM

  /** TODO: source the watchlist from config/DB; empty until then so the service
    * runs harmlessly rather than inventing watches.
    */
  private def loadWatchlist: IO[List[WatchItem]] = IO.pure(Nil)

  private def loadConfig: IO[Either[List[ConfigError], Config]] =
    IO.delay(sys.env).map { env =>
      val postal = Config.parsePostal(env.getOrElse("DEMETER_POSTAL_CODE", "H2X1Y6"))
      postal match {
        case Left(e) => Left(List(e))
        case Right(pc) =>
          val locale = if (env.get("DEMETER_LOCALE").contains("fr-ca")) Locale.FrCa else Locale.EnCa
          Config.validate(
            Config(
              postalCode = pc,
              locale = locale,
              enrichment = EnrichmentConfig(
                pcExpressEnabled = env.get("DEMETER_PCEXPRESS_ENABLED").contains("true"),
                pcExpressApiKey = env.get("DEMETER_PCEXPRESS_KEY").map(Secret.apply),
              ),
              sinks = SinkConfig(
                haWebhookUrl = env.get("DEMETER_HA_WEBHOOK"),
                haMqttTopic = env.get("DEMETER_HA_MQTT_TOPIC"),
                ntfyTopicUrl = env.get("DEMETER_NTFY_URL"),
              ),
              storage = StorageConfig(
                jdbcUrl = env.getOrElse("DEMETER_JDBC_URL", "jdbc:postgresql://localhost:5432/demeter"),
                user = env.getOrElse("DEMETER_DB_USER", "demeter"),
                password = Secret(env.getOrElse("DEMETER_DB_PASSWORD", "")),
              ),
            )
          )
      }
    }

  private def components(
      config: Config
  ): Resource[IO, (FlyerSource[IO], RawResponseStore[IO], ObservationStore[IO], FlyerLedger[IO], AlertSink[IO])] =
    for {
      client <- EmberClientBuilder.default[IO].build
      xa     <- transactor(config)
      _      <- Resource.eval(Schema.migrate(xa))
      policy <- Resource.eval(Random.scalaUtilRandom[IO].flatMap(implicit r => HttpPolicy.create[IO](config.http)))
      source = new FlippSource[IO](Http4sTransport[IO](client), policy, config.sources.flippBaseUrl)
      sink   = buildSink(config, client)
    } yield (source, new DoobieRawResponseStore[IO](xa), new DoobieObservationStore[IO](xa), new DoobieFlyerLedger[IO](xa, config.run.flyerMaxAge), sink)

  private def transactor(config: Config): Resource[IO, Transactor[IO]] =
    HikariTransactor.newHikariTransactor[IO](
      driverClassName = "org.postgresql.Driver",
      url = config.storage.jdbcUrl,
      user = config.storage.user,
      pass = config.storage.password.value,
      connectEC = scala.concurrent.ExecutionContext.global,
    )

  /** The sink chain is config-ordered; targets are never derived from flyer content (05.4). */
  private def buildSink(config: Config, client: org.http4s.client.Client[IO]): AlertSink[IO] = {
    val post: (String, String) => IO[Either[DealWatchError, Unit]] = (url, body) =>
      org.http4s.Uri.fromString(url) match {
        case Left(e) => IO.pure(Left(DealWatchError.Transport(url, e.message)))
        case Right(uri) =>
          client
            .expect[String](org.http4s.Request[IO](org.http4s.Method.POST, uri).withEntity(body))
            .attempt
            .map(_.bimap(e => DealWatchError.Transport(url, e.toString), _ => ()))
      }

    val ha = new HomeAssistantSink[IO](
      HaConfig(config.sinks.haWebhookUrl, config.sinks.haMqttTopic, config.locale),
      post,
      (_, _) => IO.pure(Left(DealWatchError.InvalidDomain("mqtt", "MQTT publishing not wired in v1"))),
    )
    val fallbacks = config.sinks.ntfyTopicUrl.map(url => new NtfySink[IO](url, config.locale, post)).toList
    new ChainSink[IO](ha, fallbacks)
  }
}
