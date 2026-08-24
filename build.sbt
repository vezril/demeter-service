import Dependencies._

ThisBuild / scalaVersion := "2.13.18"
ThisBuild / organization := "ca.demeter"
ThisBuild / scalacOptions ++= Seq(
  "-deprecation",
  "-feature",
  "-unchecked",
  "-Wunused:imports",
  "-Xsource:3",                               // ease a future Scala 3 migration
  "-Xsource-features:case-apply-copy-access", // private case-class ctor => private apply/copy (Scala 3 semantics)
  "-Ywarn-value-discard",
)

// The @boundary suites in persistence, watchlist, and alerting all talk to the
// SAME docker-compose Postgres, truncating and migrating it. sbt runs projects
// in parallel by default, so without this they race each other's DDL and the
// failures look like flakes rather than the setup problem they are.
Global / concurrentRestrictions += Tags.limit(Tags.Test, 1)

// Common settings every module shares.
lazy val commonSettings = Seq(
  libraryDependencies ++= Seq(
    scalaTest     % Test,
    scalaTestPlus % Test,
    scalaCheck    % Test,
  ),
  // Stryker4s: focus mutation on pure units; tune per-module later.
  Test / parallelExecution := true,
)

// ---- modules, in dependency order (mirrors specs/ contexts) ----

lazy val foundations = (project in file("modules/foundations"))
  .settings(name := "demeter-foundations")
  .settings(commonSettings)
  .settings(libraryDependencies ++= Seq(cats, catsEffect))

lazy val ingestion = (project in file("modules/ingestion"))
  .settings(name := "demeter-ingestion")
  .settings(commonSettings)
  .settings(libraryDependencies ++= Seq(cats, catsEffect) ++ http4s ++ circe)
  .dependsOn(foundations)

lazy val normalization = (project in file("modules/normalization"))
  .settings(name := "demeter-normalization")
  .settings(commonSettings)
  .settings(libraryDependencies ++= Seq(cats))
  .dependsOn(foundations)

lazy val persistence = (project in file("modules/persistence"))
  .settings(name := "demeter-persistence")
  .settings(commonSettings)
  .settings(libraryDependencies ++= Seq(cats, catsEffect) ++ doobie)
  .dependsOn(foundations)

lazy val watchlist = (project in file("modules/watchlist"))
  .settings(name := "demeter-watchlist")
  .settings(commonSettings)
  // owns its own persistence, the same way pricehistory does: the WatchItem model
  // lives here (04.1), so the store that reads it has to live here too — 03 sits
  // below 04 and cannot see the type.
  .settings(libraryDependencies ++= Seq(cats, catsEffect) ++ doobie)
  .dependsOn(foundations, normalization, persistence)

lazy val alerting = (project in file("modules/alerting"))
  .settings(name := "demeter-alerting")
  .settings(commonSettings)
  // persistence is declared explicitly rather than leaned on transitively: the
  // alert ledger (05.2) lives here for the same reason the watch store lives in
  // watchlist — AlertKey is a 05 type that 03 cannot see.
  .settings(libraryDependencies ++= Seq(cats, catsEffect, paho) ++ http4s ++ circe ++ doobie)
  .dependsOn(foundations, watchlist, pricehistory, persistence)

lazy val enrichment = (project in file("modules/enrichment"))
  .settings(name := "demeter-enrichment")
  .settings(commonSettings)
  .settings(libraryDependencies ++= Seq(cats, catsEffect) ++ http4s ++ circe)
  .dependsOn(foundations, ingestion, normalization)

lazy val pricehistory = (project in file("modules/pricehistory"))
  .settings(name := "demeter-pricehistory")
  .settings(commonSettings)
  .settings(libraryDependencies ++= Seq(cats, catsEffect) ++ doobie)
  .dependsOn(foundations, persistence)

lazy val orchestration = (project in file("modules/orchestration"))
  .settings(name := "demeter-orchestration")
  .settings(commonSettings)
  // Replay is a second entry point, which makes plain `run` ambiguous; name the
  // service explicitly so `run` and any packaging both mean the daily job.
  // Replay stays reachable via runMain.
  .settings(Compile / mainClass := Some("demeter.orchestration.Main"))
  // `sbt orchestration/stage` lays out bin/ + lib/ for the container image.
  .enablePlugins(JavaAppPackaging)
  .settings(libraryDependencies ++= Seq(cats, catsEffect, pureconfig, log4cats, logback))
  .dependsOn(
    foundations,
    ingestion,
    normalization,
    persistence,
    watchlist,
    alerting,
    enrichment,
    pricehistory,
  )

// Aggregate root — build/test everything from here.
lazy val root = (project in file("."))
  .settings(name := "demeter-service")
  // Stryker4s runs the CURRENT project's tests, so aggregating it just fans a
  // useless no-coverage run out across all nine modules. scripts/stryker.sh
  // drives it per module instead.
  .settings(stryker / aggregate := false)
  .aggregate(
    foundations,
    ingestion,
    normalization,
    persistence,
    watchlist,
    alerting,
    enrichment,
    pricehistory,
    orchestration,
  )
