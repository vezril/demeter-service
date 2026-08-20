package demeter.foundations

/** Spec 00.3 — Locale. Closed on purpose: only the two locales Flipp uses. */
sealed abstract class Locale(val queryValue: String) extends Product with Serializable

object Locale {
  case object FrCa extends Locale("fr-ca")
  case object EnCa extends Locale("en-ca")
}

/** Spec 00.3 — BilingualText: dumb container; the splitter lives in normalization (02.5). */
final case class BilingualText(fr: Option[String], en: Option[String]) {

  /** Preferred language, else the other, else None. */
  def primary(preferred: Locale): Option[String] =
    preferred match {
      case Locale.FrCa => fr.orElse(en)
      case Locale.EnCa => en.orElse(fr)
    }

  /** English, else French, else None. */
  def anyForm: Option[String] = en.orElse(fr)

  /** Every present language form, deduplicated — the matcher's input (04.3). */
  def forms: List[String] = (fr.toList ++ en.toList).distinct
}

object BilingualText {
  val empty: BilingualText            = BilingualText(None, None)
  def frOnly(s: String): BilingualText = BilingualText(Some(s), None)
  def enOnly(s: String): BilingualText = BilingualText(None, Some(s))
}
