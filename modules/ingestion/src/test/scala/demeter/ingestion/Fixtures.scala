package demeter.ingestion

import java.nio.file.{Files, Path, Paths}

/** Locates the repo-root `fixtures/` directory regardless of the working
  * directory sbt runs tests from (root vs module).
  */
object Fixtures {
  def path(name: String): Path = {
    val candidates = List(
      Paths.get("fixtures", name),
      Paths.get("..", "..", "fixtures", name),
    )
    candidates
      .find(Files.exists(_))
      .getOrElse(sys.error(s"fixture not found: $name (tried ${candidates.mkString(", ")})"))
  }

  def bytes(name: String): Array[Byte] = Files.readAllBytes(path(name))
}
