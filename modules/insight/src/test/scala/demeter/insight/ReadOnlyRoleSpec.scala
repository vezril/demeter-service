package demeter.insight

import java.sql.{Connection, DriverManager, SQLException}

import scala.util.Try

import org.scalatest.funsuite.AnyFunSuite

/** Spec insight-api — read-only is enforced by PostgreSQL, not by the absence of
  * write routes. Tags: @boundary.
  *
  * The distinction matters because the two guarantees fail differently: routes
  * are a property of code someone can add to, privileges are a property of the
  * database they cannot change from inside this service. The price history
  * cannot be rebuilt -- flyers expire -- so the stronger guarantee is the one
  * worth having.
  */
final class ReadOnlyRoleSpec extends AnyFunSuite {

  private val adminUrl = "jdbc:postgresql://localhost:55432/demeter_test"
  private val readUser = "demeter_read_test"
  private val readPass = "read-only-test"

  private def admin(): Connection = DriverManager.getConnection(adminUrl, "demeter", "demeter")

  /** Creates the role the way charts/demeter-insight/role.sql does. */
  private lazy val prepared: Boolean =
    Try {
      Class.forName("org.postgresql.Driver")
      val conn = admin()
      val st   = conn.createStatement()
      st.execute(s"""DO $$$$ BEGIN
                       IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = '$readUser') THEN
                         CREATE ROLE $readUser LOGIN PASSWORD '$readPass';
                       END IF;
                     END $$$$;""")
      st.execute("CREATE TABLE IF NOT EXISTS run_report_probe (id serial PRIMARY KEY, note text)")
      st.execute(s"GRANT CONNECT ON DATABASE demeter_test TO $readUser")
      st.execute(s"GRANT USAGE ON SCHEMA public TO $readUser")
      st.execute(s"GRANT SELECT ON ALL TABLES IN SCHEMA public TO $readUser")
      st.execute(s"ALTER DEFAULT PRIVILEGES IN SCHEMA public GRANT SELECT ON TABLES TO $readUser")
      st.close(); conn.close()
    }.isSuccess

  private def asReader[A](f: Connection => A): A = {
    val conn = DriverManager.getConnection(adminUrl, readUser, readPass)
    try f(conn)
    finally conn.close()
  }

  private def roleTest(name: String)(body: => Any): Unit =
    test(name) {
      assume(PgTest.available, "Postgres not reachable on localhost:55432 — run `docker compose up -d postgres`")
      assume(prepared, "could not create the read-only role")
      body
      ()
    }

  roleTest("the reader can read") {
    asReader { conn =>
      val rs = conn.createStatement().executeQuery("SELECT count(*) FROM run_report_probe")
      assert(rs.next())
    }
  }

  roleTest("the reader cannot INSERT") {
    val failure = intercept[SQLException] {
      asReader(_.createStatement().executeUpdate("INSERT INTO run_report_probe (note) VALUES ('nope')"))
    }
    assert(failure.getMessage.toLowerCase.contains("permission denied"))
  }

  roleTest("the reader cannot UPDATE or DELETE") {
    assert(Try(asReader(_.createStatement().executeUpdate("UPDATE run_report_probe SET note = 'x'"))).isFailure)
    assert(Try(asReader(_.createStatement().executeUpdate("DELETE FROM run_report_probe"))).isFailure)
  }

  roleTest("the reader cannot TRUNCATE — the one that would lose the history") {
    assert(Try(asReader(_.createStatement().execute("TRUNCATE run_report_probe"))).isFailure)
  }

  roleTest("a table created later is still readable") {
    // ALTER DEFAULT PRIVILEGES is what makes this true. Without it the next
    // migration adds a table the reader cannot see, and it looks like a bug in
    // the reader rather than a missing grant.
    val conn = admin()
    conn.createStatement().execute("CREATE TABLE IF NOT EXISTS run_report_probe_later (id int)")
    conn.close()
    asReader { c =>
      assert(c.createStatement().executeQuery("SELECT count(*) FROM run_report_probe_later").next())
    }
  }
}
