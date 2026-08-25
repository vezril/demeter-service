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

  test("the password never appears in the dump") {
    val Right(config) = Config.from(valid + ("DEMETER_DB_PASSWORD" -> "hunter2"))
    assert(!config.redactedDump.contains("hunter2"))
    assert(config.redactedDump.contains("REDACTED"))
  }
}
