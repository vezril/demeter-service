package demeter.orchestration

import cats.effect.{ExitCode, IO, IOApp, Resource}
import cats.syntax.all._
import demeter.foundations._
import demeter.ingestion.FlippDecoders
import demeter.normalization.ObservationAssembler
import demeter.persistence._
import doobie.Transactor
import doobie.hikari.HikariTransactor
import doobie.implicits._
import org.typelevel.log4cats.slf4j.Slf4jLogger

/** Spec 03.1's rebuild property, made runnable.
  *
  * "You can delete every observation and rebuild from raw_response alone" was
  * an invariant the schema was designed for and a scenario the tests covered,
  * but nothing exposed it. When a decoder bug is found — and with undocumented
  * endpoints one will be — the fix is worthless without a way to reprocess what
  * is already archived, and re-fetching instead means hammering a backend for
  * data you already hold.
  *
  *   sbt "orchestration/runMain demeter.orchestration.Replay"
  *
  * Observations are keyed on the ARCHIVE's fetch time rather than on now, so a
  * replay is idempotent: re-running it updates nothing and inserts nothing new
  * (03.3's uniqueness is on product_key + flyer_id + observed_at).
  */
object Replay extends IOApp {

  def run(args: List[String]): IO[ExitCode] =
    Slf4jLogger.create[IO].flatMap { log =>
      loadStorage.flatMap {
        case Left(why) => log.error(s"config error: $why").as(ExitCode.Error)
        case Right((storage, locale)) =>
          transactor(storage).use { xa =>
            val raws         = new DoobieRawResponseStore[IO](xa)
            val observations = new DoobieObservationStore[IO](xa)

            for {
              // merchant belongs to the flyer, not the item, so replay resolves
              // it the same way the live run does
              merchants <- sql"SELECT id, merchant_id FROM flyer".query[(Long, Int)].to[List].transact(xa)
              byFlyer = merchants.map { case (f, m) => FlyerId(f) -> MerchantId(m) }.toMap
              _ <- log.info(s"replaying against ${byFlyer.size} known flyers")
              counts <- raws
                .stream(SourceName("flipp"), ResponseKind.FlyerItems)
                .evalMap { case (rawId, raw) => replayOne(rawId, raw, byFlyer, locale, observations, log) }
                .compile
                .foldMonoid
              _ <- log.info(
                s"replay complete: ${counts._1} observations inserted, ${counts._2} already present, ${counts._3} items dropped"
              )
            } yield ExitCode.Success
          }
      }
    }

  private def replayOne(
      rawId: RawResponseId,
      raw: RawResponse,
      byFlyer: Map[FlyerId, MerchantId],
      locale: Locale,
      observations: ObservationStore[IO],
      log: org.typelevel.log4cats.Logger[IO],
  ): IO[(Int, Int, Int)] =
    FlippDecoders.parseJson("flipp", raw.bytes).flatMap(FlippDecoders.decodeItems("flipp", _)) match {
      case Left(error) => log.warn(s"raw ${rawId.value} did not decode: $error").as((0, 0, 0))
      case Right(parsed) =>
        val observed = parsed.items.flatMap { item =>
          byFlyer.get(item.flyerId).map { merchant =>
            // observedAt comes from the archive, not the clock, so replaying is
            // idempotent rather than accumulating a duplicate history each time
            ObservationAssembler.assemble(item.copy(merchantId = merchant), raw.fetchedAt, locale)
          }
        }
        observations.saveAll(observed, rawId).flatMap {
          case Left(error)  => log.warn(s"raw ${rawId.value} failed to save: $error").as((0, 0, parsed.dropped))
          case Right(saved) => IO.pure((saved.inserted, saved.skippedDuplicate, parsed.dropped))
        }
    }

  private def loadStorage: IO[Either[String, (StorageConfig, Locale)]] =
    IO.delay(sys.env).map { env =>
      Right(
        (
          StorageConfig(
            jdbcUrl = env.getOrElse("DEMETER_JDBC_URL", "jdbc:postgresql://localhost:5432/demeter"),
            user = env.getOrElse("DEMETER_DB_USER", "demeter"),
            password = Secret(env.getOrElse("DEMETER_DB_PASSWORD", "")),
          ),
          if (env.get("DEMETER_LOCALE").contains("fr-ca")) Locale.FrCa else Locale.EnCa,
        )
      )
    }

  private def transactor(storage: StorageConfig): Resource[IO, Transactor[IO]] =
    HikariTransactor.newHikariTransactor[IO](
      driverClassName = "org.postgresql.Driver",
      url = storage.jdbcUrl,
      user = storage.user,
      pass = storage.password.value,
      connectEC = scala.concurrent.ExecutionContext.global,
    )
}
