// Mutation testing — validates the test suites (Stryker4s)
addSbtPlugin("io.stryker-mutator" % "sbt-stryker4s" % "0.16.1")

// Formatting / linting — optional but nice to have wired from day one
addSbtPlugin("org.scalameta" % "sbt-scalafmt" % "2.5.2")

// Packaging — stages the orchestration module into bin/ + lib/ for the
// container image. Only `stage` is used; the plugin's own Docker support is
// deliberately not, so the Dockerfile stays readable and multi-arch.
addSbtPlugin("com.github.sbt" % "sbt-native-packager" % "1.11.7")
