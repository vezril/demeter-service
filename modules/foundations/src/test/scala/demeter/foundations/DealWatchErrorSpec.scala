package demeter.foundations

import org.scalatest.funsuite.AnyFunSuite
import DealWatchError._

/** Spec 00.5 — error taxonomy drives retry/degrade decisions. Tags: @pure. */
final class DealWatchErrorSpec extends AnyFunSuite {

  private val url = "https://backflipp.wishabi.com/flipp/flyers"

  test("each error declares the correct retriability") {
    val cases: Seq[(DealWatchError, Boolean)] = Seq(
      HttpStatus(500, url)              -> true,
      HttpStatus(503, url)              -> true,
      HttpStatus(404, url)              -> false,
      HttpStatus(429, url)              -> true,
      Timeout(url)                      -> true,
      Transport(url, "connection reset") -> true,
      BotWall(url, "cf-chl-bypass")     -> false,
      Decode("flipp", "items[3].current_price", "not numeric") -> false,
      InvalidDomain("Flyer(1)", "bad window") -> false,
      StoreConflict("price_observation", "k") -> false,
      StoreUnavailable("connection refused")  -> true,
      Unsupported("apify", "items")           -> false, // a capability gap never becomes supported by retrying
    )
    for ((err, expected) <- cases)
      assert(err.retriable == expected, s"error: $err")
  }

  test("a bot wall is non-retriable and flagged for the operator") {
    val err = BotWall(url, "cf-chl-bypass")
    assert(!err.retriable)
    assert(err.operatorAttention)
    assert(err.context("signal") == "cf-chl-bypass")
  }

  test("each error's context carries the exact keys structured logging reads") {
    // 08.3 logs on these key names and 00.5 requires the context be enough to
    // reconstruct the failure without re-hitting the network, so the key names
    // and their values are contract, not decoration.
    val cases: Seq[(DealWatchError, Map[String, String])] = Seq(
      HttpStatus(503, url)                                     -> Map("code" -> "503", "url" -> url),
      Timeout(url)                                             -> Map("url" -> url),
      Transport(url, "connection reset")                       -> Map("url" -> url, "cause" -> "connection reset"),
      BotWall(url, "cf-chl-bypass")                            -> Map("url" -> url, "signal" -> "cf-chl-bypass"),
      Unsupported("apify", "items")                            -> Map("source" -> "apify", "capability" -> "items"),
      Decode("flipp", "items[3].current_price", "not numeric") ->
        Map("source" -> "flipp", "pointer" -> "items[3].current_price", "reason" -> "not numeric"),
      InvalidDomain("Flyer(1)", "bad window")                  -> Map("what" -> "Flyer(1)", "reason" -> "bad window"),
      StoreConflict("price_observation", "k")                  -> Map("entity" -> "price_observation", "key" -> "k"),
      StoreUnavailable("connection refused")                   -> Map("cause" -> "connection refused"),
    )
    for ((err, expected) <- cases) assert(err.context == expected, s"error: $err")
  }

  test("only a bot wall demands operator attention among the transport errors") {
    assert(BotWall(url, "sig").operatorAttention)
    for (err <- Seq(HttpStatus(503, url), Timeout(url), Transport(url, "x"), Unsupported("s", "c")))
      assert(!err.operatorAttention, s"error: $err")
  }

  test("every error carries reconstructable context") {
    val all: Seq[DealWatchError] = Seq(
      HttpStatus(503, url),
      Timeout(url),
      Transport(url, "x"),
      BotWall(url, "sig"),
      Unsupported("apify", "items"),
      Decode("flipp", "items[3].current_price", "not numeric"),
      InvalidDomain("Flyer(1)", "bad window"),
      StoreConflict("flyer", "900"),
      StoreUnavailable("down"),
    )
    for (err <- all) assert(err.context.nonEmpty, s"error: $err")

    val decode = Decode("flipp", "items[3].current_price", "not numeric")
    assert(decode.context("source") == "flipp")
    assert(decode.context("pointer") == "items[3].current_price")
  }
}
