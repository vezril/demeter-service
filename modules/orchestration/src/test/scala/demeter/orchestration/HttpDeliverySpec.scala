package demeter.orchestration

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import org.http4s._
import org.http4s.client.Client
import org.http4s.dsl.io._
import org.scalatest.funsuite.AnyFunSuite

/** Spec 05.4 — a delivery is a status, not a decodable body. Tags: @pure. */
final class HttpDeliverySpec extends AnyFunSuite {

  private def clientReturning(resp: IO[Response[IO]]): Client[IO] =
    Client.fromHttpApp(HttpApp[IO](_ => resp))

  private def deliver(client: Client[IO], url: String = "http://broker/v1/topics/t/messages") = {
    // Bound to a val first: applied inline, Scala reads the next argument list
    // as the implicit one and reports "too many arguments".
    val send = HttpDelivery.sender[IO](client)
    send(url, """{"payload":"x"}""", None, true).unsafeRunSync()
  }

  test("202 with a JSON body is a delivery") {
    // The regression this file exists for. HermesMQ answers exactly this, and
    // `expect[String]` rejected it on media type: the message was published and
    // demeter recorded a transport failure, so the ledger row dedup is keyed on
    // was never written and every later run re-alerted the same deals.
    val resp = Accepted("""{"deduplicated":false,"messageId":"abc"}""")
      .map(_.withContentType(headers.`Content-Type`(MediaType.application.json)))
    assert(deliver(clientReturning(resp)) == Right(()))
  }

  test("200 with a JSON body is a delivery") {
    val resp = Ok("""{"ok":true}""").map(_.withContentType(headers.`Content-Type`(MediaType.application.json)))
    assert(deliver(clientReturning(resp)) == Right(()))
  }

  test("204 with no body at all is a delivery") {
    assert(deliver(clientReturning(NoContent())) == Right(()))
  }

  test("a plain-text 200 still delivers, so the fix did not trade one media type for another") {
    assert(deliver(clientReturning(Ok("thanks"))) == Right(()))
  }

  test("4xx is a failure, and the body is quoted to explain it") {
    val Left(err) = deliver(clientReturning(BadRequest("""{"error":"unknown topic"}""")))
    assert(err.toString.contains("400"))
    assert(err.toString.contains("unknown topic"), "the reason must survive; a bare 400 is not actionable")
  }

  test("5xx is a failure") {
    assert(deliver(clientReturning(InternalServerError("boom"))).isLeft)
  }

  test("an unparseable URL fails without attempting a request") {
    assert(deliver(clientReturning(Ok("never reached")), url = "not a url").isLeft)
  }

  test("a transport exception is a value, not a thrown error") {
    // Raised inside the effect, which is how a real client reports a refused
    // connection -- not thrown while the Resource is being built.
    val exploding = Client[IO](_ =>
      cats.effect.Resource.eval(IO.raiseError[Response[IO]](new java.net.ConnectException("connection refused")))
    )
    assert(deliver(exploding).isLeft)
  }
}
