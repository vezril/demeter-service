package demeter.alerting

import cats.effect.IO
import cats.effect.kernel.Ref
import cats.effect.unsafe.implicits.global
import demeter.foundations.{DealWatchError, Locale}
import demeter.pricehistory.DealVerdict
import io.circe.parser
import org.scalatest.funsuite.AnyFunSuite
import AlertingFixtures._

/** Specs 05.4 / 05.5 — delivery, against fake transports (never a live endpoint).
  * Tags: @boundary.
  */
final class SinksSpec extends AnyFunSuite {

  private val dealAlert = Alert.of(deal(verdict = DealVerdict.BestEver(8)), "Metro", Locale.EnCa)

  /** Records (target, body) pairs and returns a scripted outcome. */
  private def recorder(outcome: Either[DealWatchError, Unit] = Right(())) = {
    val log = Ref.of[IO, List[(String, String)]](Nil).unsafeRunSync()
    val fn: (String, String) => IO[Either[DealWatchError, Unit]] =
      (target, body) => log.update(_ :+ ((target, body))).as(outcome)
    (log, fn)
  }

  private def failing(sinkName: String) = new AlertSink[IO] {
    def name: SinkName = SinkName(sinkName)
    def deliver(a: Alert): IO[Either[DealWatchError, Unit]] =
      IO.pure(Left(DealWatchError.HttpStatus(503, s"https://$sinkName")))
  }

  private def succeeding(sinkName: String, calls: Ref[IO, List[String]]) = new AlertSink[IO] {
    def name: SinkName                                      = SinkName(sinkName)
    def deliver(a: Alert): IO[Either[DealWatchError, Unit]] = calls.update(_ :+ sinkName).as(Right(()))
  }

  // --- 05.4 Home Assistant ---

  test("a webhook delivery posts the structured alert to the configured URL") {
    val (log, post)  = recorder()
    val (_, publish) = recorder()
    val sink =
      new HomeAssistantSink[IO](HaConfig(webhookUrl = Some("http://ha.local/api/webhook/deals")), post, publish)
    assert(sink.deliver(dealAlert).unsafeRunSync() == Right(()))

    val List((target, body)) = log.get.unsafeRunSync()
    assert(target == "http://ha.local/api/webhook/deals")
    val json = parser.parse(body).toOption.get
    assert(json.hcursor.get[String]("merchant").toOption.contains("Metro"))
  }

  test("an MQTT delivery publishes to the configured topic") {
    val (_, post)      = recorder()
    val (log, publish) = recorder()
    val sink           = new HomeAssistantSink[IO](HaConfig(mqttTopic = Some("demeter/deals")), post, publish)
    sink.deliver(dealAlert).unsafeRunSync()
    assert(log.get.unsafeRunSync().map(_._1) == List("demeter/deals"))
  }

  test("a sink with no target configured fails loudly rather than silently dropping") {
    val (_, post)    = recorder()
    val (_, publish) = recorder()
    val sink         = new HomeAssistantSink[IO](HaConfig(), post, publish)
    assert(sink.deliver(dealAlert).unsafeRunSync().isLeft)
  }

  test("the sink never posts to a target derived from flyer content") {
    val (log, post)  = recorder()
    val (_, publish) = recorder()
    val hostile      = dealAlert.copy(itemName = "Milk http://evil.example/steal")
    val sink         = new HomeAssistantSink[IO](HaConfig(webhookUrl = Some("http://ha.local/hook")), post, publish)
    sink.deliver(hostile).unsafeRunSync()
    assert(log.get.unsafeRunSync().map(_._1) == List("http://ha.local/hook"))
  }

  // --- 05.5 chain / fallbacks ---

  test("the chain stops at the first successful sink") {
    val calls = Ref.of[IO, List[String]](Nil).unsafeRunSync()
    val chain = new ChainSink[IO](succeeding("ha", calls), List(succeeding("ntfy", calls), succeeding("email", calls)))
    assert(chain.deliver(dealAlert).unsafeRunSync() == Right(()))
    assert(calls.get.unsafeRunSync() == List("ha"))
  }

  test("the chain advances past a failing sink and stops at the next success") {
    val calls = Ref.of[IO, List[String]](Nil).unsafeRunSync()
    val chain = new ChainSink[IO](failing("ha"), List(succeeding("ntfy", calls), succeeding("email", calls)))
    assert(chain.deliverDetailed(dealAlert).unsafeRunSync() == Right(SinkName("ntfy")))
    assert(calls.get.unsafeRunSync() == List("ntfy")) // email never called
  }

  test("total failure is surfaced with the sinks attempted, not swallowed") {
    val chain = new ChainSink[IO](failing("ha"), List(failing("ntfy"), failing("email")))
    chain.deliverDetailed(dealAlert).unsafeRunSync() match {
      case Left(failure) =>
        assert(failure.attempted.map(_.value) == List("ha", "ntfy", "email"))
        assert(failure.errors.size == 3)
      case other => fail(s"expected total failure, got $other")
    }
    // and the AlertSink-shaped result carries the same signal as an error value
    val asError = chain.deliver(dealAlert).unsafeRunSync()
    assert(asError.isLeft)
    assert(asError.swap.exists(_.context("url").contains("ntfy")))
  }

  test("ntfy and email sinks render the plain form") {
    val (ntfyLog, post) = recorder()
    val ntfy            = new NtfySink[IO]("https://ntfy.sh/my-deals", Locale.EnCa, post)
    ntfy.deliver(dealAlert).unsafeRunSync()
    val List((target, body)) = ntfyLog.get.unsafeRunSync()
    assert(target == "https://ntfy.sh/my-deals")
    assert(body.contains("Metro") && body.contains("cheapest in 8 weeks"))

    val (mailLog, send) = recorder()
    new EmailSink[IO](Locale.EnCa, send).deliver(dealAlert).unsafeRunSync()
    assert(mailLog.get.unsafeRunSync().head._1.startsWith("Deal: "))
  }
}
