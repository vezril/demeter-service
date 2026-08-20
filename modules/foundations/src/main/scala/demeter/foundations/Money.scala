package demeter.foundations

import scala.math.BigDecimal.RoundingMode
import scala.util.Try

/** Spec 00.1 — Money as exact cents. */
sealed abstract class Currency extends Product with Serializable

object Currency {
  case object CAD extends Currency
}

sealed abstract class MoneyError extends Product with Serializable

object MoneyError {
  final case class NotANumber(input: String)           extends MoneyError
  final case class TooManyDecimalPlaces(input: String) extends MoneyError
  final case class Negative(input: String)             extends MoneyError
  final case class CurrencyMismatch(left: Currency, right: Currency) extends MoneyError
}

final case class Money private (cents: Long, currency: Currency) {

  /** Inverse of `Money.fromDecimal` for any value that parsed cleanly. */
  def format: String = f"${cents / 100}%d.${cents % 100}%02d"

  def +(that: Money): Either[MoneyError, Money] =
    if (currency == that.currency) Right(Money(cents + that.cents, currency))
    else Left(MoneyError.CurrencyMismatch(currency, that.currency))

  /** Per-unit share of this total across `n` units, banker's rounding (half-even). */
  def divideEvenly(n: Int): Money = {
    require(n > 0, s"division count must be positive, got $n")
    Money((BigDecimal(cents) / n).setScale(0, RoundingMode.HALF_EVEN).toLongExact, currency)
  }
}

object Money {

  // Canonical decimal only: optional sign, digits, optional fraction. Comma-decimal,
  // currency symbols, grouping etc. are the normalizer's job (02.1), rejected here.
  private val DecimalShape = """(-?)(\d+)(?:\.(\d+))?""".r

  def cents(n: Long, currency: Currency = Currency.CAD): Money = Money(n, currency)

  def fromDecimal(s: String, currency: Currency = Currency.CAD): Either[MoneyError, Money] =
    s match {
      case DecimalShape(sign, whole, frac) =>
        if (sign == "-") Left(MoneyError.Negative(s))
        else if (frac != null && frac.length > 2) Left(MoneyError.TooManyDecimalPlaces(s))
        else
          Try {
            val fracCents = Option(frac).map(_.padTo(2, '0').toLong).getOrElse(0L)
            Money(Math.addExact(Math.multiplyExact(whole.toLong, 100L), fracCents), currency)
          }.toEither.left.map(_ => MoneyError.NotANumber(s))
      case _ => Left(MoneyError.NotANumber(s))
    }

  def fromBigDecimal(d: BigDecimal, currency: Currency = Currency.CAD): Either[MoneyError, Money] =
    if (d < 0) Left(MoneyError.Negative(d.toString))
    else {
      val scaled = d * 100
      if (!scaled.isWhole) Left(MoneyError.TooManyDecimalPlaces(d.toString))
      else Try(Money(scaled.toLongExact, currency)).toEither.left.map(_ => MoneyError.NotANumber(d.toString))
    }
}
