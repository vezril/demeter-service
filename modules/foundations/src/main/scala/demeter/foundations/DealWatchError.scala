package demeter.foundations

/** Spec 00.5 — one taxonomy of failure for the whole service.
  *
  * The `retriable` flag is the boundary between "recoverable, degrade this
  * source" and "bug / operator problem, fail loud". The orchestrator (08.2)
  * decides per case; nothing here performs retries itself.
  */
sealed trait DealWatchError extends Product with Serializable {
  def retriable: Boolean
  def context: Map[String, String]

  /** True for the failure modes that should page the operator (the "Flipp added auth" class). */
  def operatorAttention: Boolean = false
}

object DealWatchError {

  // --- transport (boundary 01/06) ---

  final case class HttpStatus(code: Int, url: String) extends DealWatchError {
    val retriable: Boolean           = code >= 500 || code == 429
    val context: Map[String, String] = Map("code" -> code.toString, "url" -> url)
  }

  final case class Timeout(url: String) extends DealWatchError {
    val retriable: Boolean           = true
    val context: Map[String, String] = Map("url" -> url)
  }

  final case class Transport(url: String, cause: String) extends DealWatchError {
    val retriable: Boolean           = true
    val context: Map[String, String] = Map("url" -> url, "cause" -> cause)
  }

  /** 403 / Cloudflare challenge / captcha — the signal Flipp put the endpoint behind auth. */
  final case class BotWall(url: String, signal: String) extends DealWatchError {
    val retriable: Boolean                  = false
    val context: Map[String, String]        = Map("url" -> url, "signal" -> signal)
    override val operatorAttention: Boolean = true
  }

  /** A capability the source honestly does not support (01.1); never a network event. */
  final case class Unsupported(source: String, capability: String) extends DealWatchError {
    val retriable: Boolean           = false
    val context: Map[String, String] = Map("source" -> source, "capability" -> capability)
  }

  // --- decode (01.5) ---

  final case class Decode(source: String, pointer: String, reason: String) extends DealWatchError {
    val retriable: Boolean           = false
    val context: Map[String, String] = Map("source" -> source, "pointer" -> pointer, "reason" -> reason)
  }

  // --- domain (00.x, 02) ---

  final case class InvalidDomain(what: String, reason: String) extends DealWatchError {
    val retriable: Boolean           = false
    val context: Map[String, String] = Map("what" -> what, "reason" -> reason)
  }

  // --- persistence (03) ---

  final case class StoreConflict(entity: String, key: String) extends DealWatchError {
    val retriable: Boolean           = false
    val context: Map[String, String] = Map("entity" -> entity, "key" -> key)
  }

  final case class StoreUnavailable(cause: String) extends DealWatchError {
    val retriable: Boolean           = true
    val context: Map[String, String] = Map("cause" -> cause)
  }
}
