package demeter.orchestration

import scala.concurrent.duration._

import cats.effect.{ExitCode, IO, IOApp, Resource}
import cats.effect.std.Random
import cats.syntax.all._
import demeter.alerting._
import demeter.foundations._
import demeter.ingestion._
import demeter.persistence._
import demeter.watchlist.{DoobieWatchStore, WatchItem, WatchStore}
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
            components(config).use { case (source, rawStore, observations, ledger, sink, watches, alertLedger) =>
              for {
                watchlist <- loadWatchlist(watches, log)
                run       <- DailyRun.create[IO](source, None, rawStore, observations, ledger, sink, alertLedger, config, watchlist)
                _         <- IO.whenA(config.schedule.runOnStart)(executeOnce(run, log).void)
                // validated at boot, so this cannot fail here
                schedule   = DailySchedule.parse(config.schedule.cron, config.schedule.zone)
                               .getOrElse(throw new IllegalStateException("schedule passed validation but will not parse"))
                _         <- loop(run, log, schedule)
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

  /** Sleeps until the next scheduled wall-clock firing, recomputed from the
    * clock each time. Accumulating a fixed interval instead would let a slow
    * run drag the schedule later every day, and would tie firing to process
    * start rather than to a time of day.
    */
  private def loop(
      run: DailyRun[IO],
      log: org.typelevel.log4cats.Logger[IO],
      schedule: DailySchedule,
  ): IO[Unit] =
    (for {
      now  <- IO.realTimeInstant
      next  = schedule.nextAfter(now)
      wait  = math.max(0L, next.toEpochMilli - now.toEpochMilli).millis
      _    <- log.info(s"next run at $next (in ${wait.toMinutes} min)")
      _    <- IO.sleep(wait)
      _    <- executeOnce(run, log).attempt.flatMap {
        // a failed run must not break the loop — tomorrow still gets a turn
        case Left(e)  => log.error(s"run failed: $e")
        case Right(_) => IO.unit
      }
    } yield ()).foreverM

  /** The watchlist comes from the database (04.1 / the watch_item table).
    *
    * An empty watchlist is legal but useless — the run would match nothing — so
    * it is called out loudly at boot rather than left for you to discover from
    * a week of silent runs. Rows the domain rejects are named individually.
    */
  private def loadWatchlist(store: WatchStore[IO], log: org.typelevel.log4cats.Logger[IO]): IO[List[WatchItem]] =
    store.load.flatMap { loaded =>
      val warnRejects = loaded.rejected.traverse_ { case (id, why) =>
        log.error(s"watch '$id' is stored but invalid and will be skipped: $why")
      }
      val warnEmpty = IO.whenA(loaded.items.count(_.active) == 0)(
        log.warn("no ACTIVE watches configured — the run will fetch and store prices but alert on nothing")
      )
      warnRejects *> warnEmpty *>
        log.info(s"loaded ${loaded.items.count(_.active)} active watch(es) of ${loaded.items.size}") *>
        IO.pure(loaded.items)
    }

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
              schedule = ScheduleConfig(
                cron = env.getOrElse("DEMETER_SCHEDULE_CRON", "0 6 * * *"),
                zone = env.get("DEMETER_SCHEDULE_ZONE")
                  .flatMap(z => scala.util.Try(java.time.ZoneId.of(z)).toOption)
                  .getOrElse(java.time.ZoneId.of("America/Montreal")),
              ),
              sinks = SinkConfig(
                haWebhookUrl = env.get("DEMETER_HA_WEBHOOK"),
                haMqttTopic = env.get("DEMETER_HA_MQTT_TOPIC"),
                ntfyTopicUrl = env.get("DEMETER_NTFY_URL"),
                mqttBrokerUrl = env.get("DEMETER_MQTT_BROKER"),
                mqttUsername = env.get("DEMETER_MQTT_USER"),
                mqttPassword = env.get("DEMETER_MQTT_PASSWORD").map(Secret.apply),
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
  ): Resource[IO, (FlyerSource[IO], RawResponseStore[IO], ObservationStore[IO], FlyerLedger[IO], AlertSink[IO], WatchStore[IO], AlertLedger[IO])] =
    for {
      client <- EmberClientBuilder.default[IO].build
      xa     <- transactor(config)
      _      <- Resource.eval(Schema.migrate(xa))
      policy <- Resource.eval(Random.scalaUtilRandom[IO].flatMap(implicit r => HttpPolicy.create[IO](config.http)))
      source = new FlippSource[IO](Http4sTransport[IO](client), policy, config.sources.flippBaseUrl)
      // only opened when a topic is actually configured — no broker connection
      // is made for a webhook-only deployment
      publish <- mqttPublisher(config)
      sink   = buildSink(config, client, publish)
    } yield (
      source,
      new DoobieRawResponseStore[IO](xa),
      new DoobieObservationStore[IO](xa),
      new DoobieFlyerLedger[IO](xa, config.run.flyerMaxAge),
      sink,
      new DoobieWatchStore[IO](xa),
      new DoobieAlertLedger[IO](xa),
    )

  private def transactor(config: Config): Resource[IO, Transactor[IO]] =
    HikariTransactor.newHikariTransactor[IO](
      driverClassName = "org.postgresql.Driver",
      url = config.storage.jdbcUrl,
      user = config.storage.user,
      pass = config.storage.password.value,
      connectEC = scala.concurrent.ExecutionContext.global,
    )

  /** Connects only if an MQTT topic is configured; otherwise yields a publisher
    * that reports the misconfiguration rather than pretending to deliver.
    */
  private def mqttPublisher(config: Config): Resource[IO, (String, String) => IO[Either[DealWatchError, Unit]]] =
    (config.sinks.haMqttTopic, config.sinks.mqttBrokerUrl) match {
      case (Some(_), Some(broker)) =>
        MqttPublisher.resource[IO](
          MqttConfig(
            brokerUrl = broker,
            username = config.sinks.mqttUsername,
            password = config.sinks.mqttPassword.map(_.value),
          )
        )
      case _ =>
        Resource.pure[IO, (String, String) => IO[Either[DealWatchError, Unit]]]((_, _) =>
          IO.pure(Left(DealWatchError.InvalidDomain("mqtt", "no MQTT broker configured")))
        )
    }

  /** The sink chain is config-ordered; targets are never derived from flyer content (05.4). */
  private def buildSink(
      config: Config,
      client: org.http4s.client.Client[IO],
      publish: (String, String) => IO[Either[DealWatchError, Unit]],
  ): AlertSink[IO] = {
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
      publish,
    )
    val fallbacks = config.sinks.ntfyTopicUrl.map(url => new NtfySink[IO](url, config.locale, post)).toList
    new ChainSink[IO](ha, fallbacks)
  }
}
