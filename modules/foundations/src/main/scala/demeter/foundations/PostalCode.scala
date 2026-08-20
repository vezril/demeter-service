package demeter.foundations

/** Spec 00.2 — Canadian postal code. */
sealed abstract class PostalCodeError extends Product with Serializable

object PostalCodeError {
  final case class WrongShape(input: String)                  extends PostalCodeError
  final case class IllegalLetter(input: String, letter: Char) extends PostalCodeError
}

final case class PostalCode private (fsa: String, ldu: String) {
  def canonical: String = fsa + ldu
  def withSpace: String = s"$fsa $ldu"
}

object PostalCode {

  /** Letters Canada Post never uses in postal codes. */
  val NeverUsedLetters: Set[Char] = Set('D', 'F', 'I', 'O', 'Q', 'U')

  /** Additionally never valid as the first letter. */
  val InvalidFirstLetters: Set[Char] = NeverUsedLetters ++ Set('W', 'Z')

  private val Shape = """([A-Za-z])(\d)([A-Za-z]) ?(\d)([A-Za-z])(\d)""".r

  def parse(s: String): Either[PostalCodeError, PostalCode] =
    s match {
      case Shape(l1, d1, l2, d2, l3, d3) =>
        val letters = List(l1, l2, l3).map(_.head.toUpper)
        val illegal =
          letters.headOption.filter(InvalidFirstLetters).orElse(letters.tail.find(NeverUsedLetters))
        illegal match {
          case Some(letter) => Left(PostalCodeError.IllegalLetter(s, letter))
          case None =>
            val List(c1, c2, c3) = letters
            Right(PostalCode(s"$c1$d1$c2", s"$d2$c3$d3"))
        }
      case _ => Left(PostalCodeError.WrongShape(s))
    }
}
