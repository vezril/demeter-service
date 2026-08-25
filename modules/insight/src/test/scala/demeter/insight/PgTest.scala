package demeter.insight

import java.sql.DriverManager

import scala.util.Try

import cats.effect.IO
import doobie.Transactor

/** Same harness as the persistence suites: connects to the docker-compose
  * Postgres, and cancels rather than fails when it isn't up.
  */
object PgTest {
  private val url  = "jdbc:postgresql://localhost:55432/demeter_test"
  private val user = "demeter"
  private val pass = "demeter"

  lazy val available: Boolean =
    Try {
      Class.forName("org.postgresql.Driver")
      DriverManager.getConnection(url, user, pass).close()
    }.isSuccess

  lazy val xa: Transactor[IO] =
    Transactor.fromDriverManager[IO]("org.postgresql.Driver", url, user, pass, None)
}
