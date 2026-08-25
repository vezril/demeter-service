package demeter.insight

import cats.effect.{ExitCode, IO, IOApp, Resource}
import com.comcast.ip4s._
import doobie.Transactor
import doobie.hikari.HikariTransactor
import org.http4s.ember.server.EmberServerBuilder
import org.http4s.server.Router
import org.typelevel.log4cats.slf4j.Slf4jLogger

/** demeter-insight: a read-only reader over the demeter schema.
  *
  * Unlike demeter-service this DOES bind a socket -- that is the point of it,
  * and the reason it is a separate service. The daily run keeps its own process
  * and failure domain, so a request pattern here cannot affect a fetch against
  * a rate-limited upstream.
  */
object Main extends IOApp {

  def run(args: List[String]): IO[ExitCode] =
    Slf4jLogger.create[IO].flatMap { log =>
      Config.load.flatMap {
        case Left(errors) =>
          errors.traverse_(e => log.error(s"config error: $e")).as(ExitCode.Error)
        case Right(config) =>
          log.info(s"starting demeter-insight\n${config.redactedDump}") *>
            transactor(config).use { xa =>
              val routes = new Routes[IO](
                new DoobieRunQueries[IO](xa),
                new DbHistoryQueries[IO](xa),
                new DbWatchQueries[IO](xa),
              ).routes
              EmberServerBuilder
                .default[IO]
                .withHost(host"0.0.0.0")
                .withPort(Port.fromInt(config.port).getOrElse(port"8080"))
                .withHttpApp(Router("/" -> routes).orNotFound)
                .build
                .use(_ => log.info(s"listening on 0.0.0.0:${config.port}") *> IO.never)
                .as(ExitCode.Success)
            }
      }
    }

  private def transactor(config: Config): Resource[IO, Transactor[IO]] =
    HikariTransactor.newHikariTransactor[IO](
      driverClassName = "org.postgresql.Driver",
      url = config.jdbcUrl,
      user = config.user,
      pass = config.password,
      connectEC = scala.concurrent.ExecutionContext.global,
    )

  private implicit class TraverseOps[A](val list: List[A]) extends AnyVal {
    def traverse_(f: A => IO[Unit]): IO[Unit] = list.foldLeft(IO.unit)((acc, a) => acc *> f(a))
  }
}
