package demeter.normalization

import java.nio.charset.StandardCharsets
import java.security.MessageDigest

import demeter.foundations.{BilingualText, MerchantId, ProductKey, Size}

/** Spec 02.7 — our own stable product key, because Flipp's item ids change
  * weekly. Merchant-scoped + normalized name tokens + size, hashed, with the
  * normalization version stamped in so a future algorithm change migrates
  * history deliberately instead of silently orphaning it. Cross-language and
  * cross-merchant identity are explicitly NOT this key's job (07's concern).
  */
object ProductKeys {

  val Version = "v1"

  def productKey(merchantId: MerchantId, name: BilingualText, size: Option[Size]): ProductKey = {
    val primaryForm = name.anyForm.getOrElse("")
    val tokens      = TextNormalizer.normalize(primaryForm).tokens
    val sizePart = size
      .map(s => s"${s.quantity.bigDecimal.stripTrailingZeros.toPlainString}:${s.unit}:${s.packCount}")
      .getOrElse("nosize")
    val payload = s"${merchantId.value}|${tokens.mkString(" ")}|$sizePart"
    ProductKey(s"$Version:${sha256Hex(payload).take(32)}")
  }

  private def sha256Hex(s: String): String =
    MessageDigest
      .getInstance("SHA-256")
      .digest(s.getBytes(StandardCharsets.UTF_8))
      .map(b => f"$b%02x")
      .mkString
}
