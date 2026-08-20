package demeter.foundations

import org.scalatest.funsuite.AnyFunSuite

/** Spec 00.2 — Canadian postal code. Tags: @pure. */
final class PostalCodeSpec extends AnyFunSuite {

  test("valid postal codes parse and canonicalize") {
    val cases = Seq(
      ("H2X 1Y6", "H2X1Y6", "H2X"),
      ("h2x1y6", "H2X1Y6", "H2X"),
      ("H2X1Y6", "H2X1Y6", "H2X"),
      ("K1A 0B1", "K1A0B1", "K1A"),
    )
    for ((input, canonical, fsa) <- cases) {
      val Right(pc) = PostalCode.parse(input)
      assert(pc.canonical == canonical, s"input: $input")
      assert(pc.fsa == fsa, s"input: $input")
    }
  }

  test("structurally invalid inputs are rejected") {
    for (input <- Seq("12345", "H2X", "H2X-1Y6", "HH2 1Y6", ""))
      assert(PostalCode.parse(input) == Left(PostalCodeError.WrongShape(input)), s"input: $input")
  }

  test("letters excluded by Canada Post are rejected") {
    val cases = Seq(
      "D2X 1Y6" -> 'D', // D never used
      "H2I 1Y6" -> 'I', // I never used
      "W2X 1Y6" -> 'W', // W not valid as first letter
      "H2X 1O6" -> 'O', // O never used
    )
    for ((input, letter) <- cases)
      assert(PostalCode.parse(input) == Left(PostalCodeError.IllegalLetter(input, letter)), s"input: $input")
  }

  test("display form re-inserts the space") {
    val Right(pc) = PostalCode.parse("H2X1Y6")
    assert(pc.withSpace == "H2X 1Y6")
  }
}
