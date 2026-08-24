import sbt._

/** Centralized dependency versions and modules.
  * Keeping these in one object means a version bump is a one-line change
  * and every module pulls a consistent set.
  */
object Dependencies {

  object V {
    val cats          = "2.12.0"
    val catsEffect    = "3.7-4972921"
    val http4s        = "0.23.27"
    val circe         = "0.14.9"
    val doobie        = "1.0.0-RC5"
    val pureconfig    = "0.17.7"
    val log4cats      = "2.7.0"
    val logback       = "1.5.6"
    val paho          = "1.2.5"
    val scalaTest     = "3.2.19"
    val scalaTestPlus = "3.2.19.0"
    val scalaCheck    = "1.18.0"
  }

  // --- core ---
  val cats       = "org.typelevel" %% "cats-core"   % V.cats
  val catsEffect = "org.typelevel" %% "cats-effect" % V.catsEffect

  // --- http client/server (ingestion, enrichment, alerting sinks) ---
  val http4s = Seq(
    "org.http4s" %% "http4s-ember-client" % V.http4s,
    "org.http4s" %% "http4s-ember-server" % V.http4s,
    "org.http4s" %% "http4s-circe"        % V.http4s,
    "org.http4s" %% "http4s-dsl"          % V.http4s,
  )

  // --- json (Flipp decoders) ---
  val circe = Seq(
    "io.circe" %% "circe-core"    % V.circe,
    "io.circe" %% "circe-parser"  % V.circe,
    "io.circe" %% "circe-generic" % V.circe,
  )

  // --- postgres access ---
  val doobie = Seq(
    "org.tpolecat" %% "doobie-core"     % V.doobie,
    "org.tpolecat" %% "doobie-postgres" % V.doobie,
    "org.tpolecat" %% "doobie-hikari"   % V.doobie,
  )

  // --- mqtt (the Home Assistant alert sink, 05.4) ---
  val paho = "org.eclipse.paho" % "org.eclipse.paho.client.mqttv3" % V.paho

  // --- config / logging (orchestration) ---
  val pureconfig = "com.github.pureconfig" %% "pureconfig"      % V.pureconfig
  val log4cats   = "org.typelevel"         %% "log4cats-slf4j"  % V.log4cats
  val logback    = "ch.qos.logback"         % "logback-classic" % V.logback

  // --- test ---
  val scalaTest     = "org.scalatest"     %% "scalatest"       % V.scalaTest
  val scalaTestPlus = "org.scalatestplus" %% "scalacheck-1-18" % V.scalaTestPlus
  val scalaCheck    = "org.scalacheck"    %% "scalacheck"      % V.scalaCheck
}
