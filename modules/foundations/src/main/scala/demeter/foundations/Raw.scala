package demeter.foundations

import java.time.Instant

/** Shared by ingestion (01.1 — sources return raw + parsed) and persistence
  * (03.2 — the raw archive). Lives in foundations because persistence must not
  * depend on ingestion.
  */
final case class SourceName(value: String) extends AnyVal

/** The exact bytes of one upstream fetch, archived before anything trusts a parse. */
final case class RawResponse(bytes: Array[Byte], contentType: String, fetchedAt: Instant, url: String)
