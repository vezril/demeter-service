package demeter.insight

import org.scalatest.funsuite.AnyFunSuite

/** Spec insight-api — fail-fast configuration. Tags: @pure. */
final class ConfigSpec extends AnyFunSuite {

  private val valid =
    Map("DEMETER_JDBC_URL" -> "jdbc:postgresql://db:5432/demeter", "DEMETER_DB_USER" -> "demeter_read")

  test("a complete environment loads") {
    val Right(config) = Config.from(valid)
    assert(config.jdbcUrl.contains("demeter"))
    assert(config.port == 8080, "the default port is used when none is set")
  }

  test("there is no default database URL") {
    // A reader silently pointed at the wrong database returns plausible numbers
    // about the wrong thing, which is worse than refusing to start.
    assert(Config.from(valid - "DEMETER_JDBC_URL").isLeft)
  }

  test("every problem is reported, not just the first") {
    val Left(errors) = Config.from(Map.empty)
    assert(errors.size >= 2, "a misconfigured deployment should be fixable in one pass")
  }

  test("a nonsense port is rejected rather than defaulted") {
    assert(Config.from(valid + ("DEMETER_INSIGHT_PORT" -> "nope")).isLeft)
    assert(Config.from(valid + ("DEMETER_INSIGHT_PORT" -> "70000")).isLeft)
    assert(Config.from(valid + ("DEMETER_INSIGHT_PORT" -> "9090")).isRight)
  }

  test("watch editing is off unless a write password is supplied") {
    // The safe default: without it the write routes are never mounted, so a
    // deployment that forgot the role is read-only rather than broken.
    val Right(off) = Config.from(valid)
    assert(off.watchPassword.isEmpty)
    assert(off.redactedDump.contains("read-only"))

    val Right(on) = Config.from(valid + ("DEMETER_WATCH_PASSWORD" -> "s3cret"))
    assert(on.watchPassword.isDefined)
    assert(on.redactedDump.contains("enabled"))
  }

  test("neither password appears in the dump") {
    val Right(c) = Config.from(valid ++ Map("DEMETER_DB_PASSWORD" -> "hunter2", "DEMETER_WATCH_PASSWORD" -> "hunter3"))
    assert(!c.redactedDump.contains("hunter2"))
    assert(!c.redactedDump.contains("hunter3"))
  }

  test("the password never appears in the dump") {
    val Right(config) = Config.from(valid + ("DEMETER_DB_PASSWORD" -> "hunter2"))
    assert(!config.redactedDump.contains("hunter2"))
    assert(config.redactedDump.contains("REDACTED"))
  }
}
