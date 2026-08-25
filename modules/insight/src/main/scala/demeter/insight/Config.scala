package demeter.insight

import cats.effect.IO

/** Environment-only configuration, validated fail-fast like the daily job's.
  *
  * There is no default JDBC URL: a reader that silently points at the wrong
  * database returns plausible numbers about the wrong thing, which is worse
  * than refusing to start.
  */
final case class Config(
    jdbcUrl: String,
    user: String,
    password: String,
    port: Int,
) {
  def redactedDump: String =
    List(
      s"jdbcUrl=$jdbcUrl",
      s"user=$user",
      s"password=${if (password.isEmpty) "unset" else "***REDACTED***"}",
      s"port=$port",
    ).mkString("\n")
}

object Config {

  def load: IO[Either[List[String], Config]] =
    IO.delay(sys.env).map(from)

  def from(env: Map[String, String]): Either[List[String], Config] = {
    val url  = env.get("DEMETER_JDBC_URL").filter(_.trim.nonEmpty)
    val user = env.get("DEMETER_DB_USER").filter(_.trim.nonEmpty)
    val port = env.get("DEMETER_INSIGHT_PORT").map(_.trim)

    val portValue = port match {
      case None    => Right(8080)
      case Some(p) => p.toIntOption.filter(v => v > 0 && v <= 65535).toRight(s"invalid DEMETER_INSIGHT_PORT: $p")
    }

    val errors = List(
      if (url.isEmpty) Some("DEMETER_JDBC_URL is not set") else None,
      if (user.isEmpty) Some("DEMETER_DB_USER is not set") else None,
      portValue.left.toOption,
    ).flatten

    if (errors.nonEmpty) Left(errors)
    else
      Right(
        Config(
          jdbcUrl = url.get,
          user = user.get,
          password = env.getOrElse("DEMETER_DB_PASSWORD", ""),
          port = portValue.getOrElse(8080),
        )
      )
  }
}
