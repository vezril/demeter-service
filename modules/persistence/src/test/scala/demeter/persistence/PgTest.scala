package demeter.persistence

import java.sql.DriverManager

import scala.util.Try

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import doobie.Transactor
import doobie.implicits._

/** Shared harness for the @boundary suites: connects to the docker-compose
  * Postgres (see docker-compose.yml); suites `assume` availability so a
  * machine without the container skips rather than fails.
  */
object PgTest {

  private val url  = "jdbc:postgresql://localhost:55432/demeter_test"
  private val user = "demeter"
  private val pass = "demeter"

  lazy val available: Boolean =
    Try {
      Class.forName("org.postgresql.Driver")
      val conn = DriverManager.getConnection(url, user, pass)
      conn.close()
    }.isSuccess

  lazy val xa: Transactor[IO] =
    Transactor.fromDriverManager[IO](
      driver = "org.postgresql.Driver",
      url = url,
      user = user,
      password = pass,
      logHandler = None,
    )

  /** Migrate once, then truncate between suites/tests for isolation. */
  lazy val migrated: Unit = Schema.migrate(xa).unsafeRunSync()

  def truncateAll(): Unit =
    sql"""TRUNCATE price_observation, product, flyer_fetch_ledger, flyer, merchant, raw_response RESTART IDENTITY CASCADE""".update.run
      .transact(xa)
      .void
      .unsafeRunSync()
}
