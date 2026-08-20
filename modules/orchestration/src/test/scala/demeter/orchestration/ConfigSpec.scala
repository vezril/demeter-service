package demeter.orchestration

import java.time.Duration

import demeter.foundations.{Locale, PostalCode}
import org.scalatest.funsuite.AnyFunSuite

/** Spec 08.4 — validated fail-fast configuration. Tags: @boundary/@pure. */
final class ConfigSpec extends AnyFunSuite {

  private val postal = PostalCode.parse("H2X1Y6").toOption.get

  private def config(
      enrichment: EnrichmentConfig = EnrichmentConfig(),
      sources: SourceConfig = SourceConfig(),
      sinks: SinkConfig = SinkConfig(haWebhookUrl = Some("http://ha.local/hook")),
      run: RunConfig = RunConfig(),
      history: HistoryConfig = HistoryConfig(),
  ) = Config(postal, Locale.EnCa, sources = sources, enrichment = enrichment, sinks = sinks, run = run, history = history)

  test("an invalid postal code stops startup with a clear error") {
    val result = Config.parsePostal("12345")
    assert(result.isLeft)
    assert(result.swap.toOption.get.message.contains("12345"))
    assert(Config.parsePostal("H2X 1Y6").isRight)
  }

  test("enabling a source without its key fails at boot, naming the key") {
    val errors = Config
      .validate(config(enrichment = EnrichmentConfig(pcExpressEnabled = true, pcExpressApiKey = None)))
      .swap
      .toOption
      .get
    assert(errors.exists(_.message.contains("pcExpressApiKey")))
  }

  test("a supplied key satisfies the check") {
    val valid = config(enrichment = EnrichmentConfig(pcExpressEnabled = true, pcExpressApiKey = Some(Secret("k"))))
    assert(Config.validate(valid).isRight)
  }

  test("an enabled Apify fallback without a token fails at boot") {
    val errors = Config.validate(config(sources = SourceConfig(apifyFallbackEnabled = true))).swap.toOption.get
    assert(errors.exists(_.message.contains("apifyToken")))
  }

  test("an empty sink chain is rejected — alerts would have nowhere to go") {
    val errors = Config.validate(config(sinks = SinkConfig())).swap.toOption.get
    assert(errors.contains(ConfigError.EmptySinkChain))
  }

  test("out-of-range values are rejected") {
    val errors = Config
      .validate(config(run = RunConfig(flyerConcurrency = 0), history = HistoryConfig(window = Duration.ZERO)))
      .swap
      .toOption
      .get
    assert(errors.exists(_.message.contains("flyerConcurrency")))
    assert(errors.exists(_.message.contains("history.window")))
  }

  test("every problem is reported at once, not just the first") {
    val broken = config(
      enrichment = EnrichmentConfig(pcExpressEnabled = true),
      sinks = SinkConfig(),
      run = RunConfig(flyerConcurrency = 0),
    )
    assert(Config.validate(broken).swap.toOption.get.size >= 3)
  }

  test("an MQTT topic with no broker is refused: it looks configured and delivers nothing") {
    val errors = Config
      .validate(config(sinks = SinkConfig(haMqttTopic = Some("demeter/deals"))))
      .swap.toOption.get
    assert(errors.exists(_.message.contains("mqttBrokerUrl")))
  }

  test("a complete MQTT sink configuration passes") {
    val c = config(sinks = SinkConfig(haMqttTopic = Some("demeter/deals"), mqttBrokerUrl = Some("tcp://ha.local:1883")))
    assert(Config.validate(c).isRight)
  }

  test("an unrunnable schedule stops startup instead of surfacing a day later") {
    // the old loop ignored cron entirely; a typo now has to fail at boot, not
    // leave the service sitting idle looking healthy
    val errors = Config.validate(config().copy(schedule = ScheduleConfig(cron = "every morning"))).swap.toOption.get
    assert(errors.exists(_.message.contains("schedule.cron")))
    assert(errors.exists(_.message.contains("5 cron fields")))
  }

  test("a schedule this service cannot honour is refused rather than approximated") {
    val monthly = Config.validate(config().copy(schedule = ScheduleConfig(cron = "0 6 1 * *"))).swap.toOption.get
    assert(monthly.exists(_.message.contains("day-of-month")))
  }

  test("a valid schedule passes and is rendered readably in the dump") {
    val c = config().copy(schedule = ScheduleConfig(cron = "30 18 * * 4"))
    assert(Config.validate(c).isRight)
    assert(c.redactedDump.contains("18:30"))
    assert(c.redactedDump.contains("thu"))
  }

  test("a config dump redacts secrets but keeps diagnostics readable") {
    val dumped = config(enrichment = EnrichmentConfig(pcExpressEnabled = true, pcExpressApiKey = Some(Secret("SUPER-SECRET"))))
      .copy(storage = StorageConfig(password = Secret("hunter2")))
      .redactedDump

    assert(!dumped.contains("SUPER-SECRET"))
    assert(!dumped.contains("hunter2"))
    assert(dumped.contains("REDACTED"))
    assert(dumped.contains("H2X1Y6"))       // non-secret settings stay visible
    assert(dumped.contains("flyerConcurrency"))
  }

  test("a Secret never leaks through toString or interpolation") {
    val s = Secret("leak-me")
    assert(s.toString == "***REDACTED***")
    assert(!s"$s".contains("leak-me"))
    assert(s.value == "leak-me") // still reachable deliberately, for the call that needs it
  }

  test("the postal code appears once, so multi-postal is an additive change") {
    val c = config()
    assert(c.postalCode == postal)
    assert(c.redactedDump.linesIterator.count(_.startsWith("postalCode=")) == 1)
  }
}
