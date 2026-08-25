package demeter.insight

import java.time.Instant

import io.circe.Encoder
import io.circe.generic.semiauto.deriveEncoder

/** The wire model for a run, owned by this service rather than shared with
  * `orchestration`.
  *
  * Deliberately a separate type from `RunReport`: that one is the batch job's
  * internal accumulator, and letting it double as an API contract means an
  * internal refactor silently reshapes a public response. This type changes only
  * when the API is meant to change.
  */
final case class RunView(
    id: Long,
    startedAt: Instant,
    finishedAt: Instant,
    elapsedSeconds: Option[Long],
    flyers: FlyerCounts,
    items: ItemCounts,
    observations: ObservationCounts,
    alerts: AlertCounts,
    degradedSources: List[String],
    failures: List[String],
    partial: Boolean,
)

final case class FlyerCounts(listed: Int, selected: Int, fetched: Int, failed: Int)

final case class ItemCounts(parsed: Int, dropped: Int) {

  /** Surfaced rather than left to the caller: this is the drift signal (08.3),
    * and a client recomputing it would be free to compute it differently.
    */
  def decodeFailureRate: Double =
    if (parsed + dropped == 0) 0.0 else dropped.toDouble / (parsed + dropped)
}

final case class ObservationCounts(inserted: Int, skipped: Int)

final case class AlertCounts(
    matched: Int,
    delivered: Int,
    suppressed: Int,
    /** Per reason, never collapsed to a total. A bare count cannot tell a price
      * ceiling that is too tight from an empty history from having already told
      * you, and those want three different responses.
      */
    suppressedByReason: Map[String, Int],
    /** None means the sink could not say, which is NOT zero. Encoded as null so
      * a client cannot read "could not ask" as "nobody listening".
      */
    audience: Option[Int],
) {

  /** Matches that neither alerted nor were suppressed. Non-zero means deliveries
    * were attempted and failed -- the exact shortfall that hid a working publish
    * being recorded as a transport failure, found only by reading a log line
    * against the broker's own counter.
    */
  def unaccounted: Int = matched - delivered - suppressed
}

object RunView {
  implicit val flyerEncoder: Encoder[FlyerCounts] = deriveEncoder
  implicit val itemEncoder: Encoder[ItemCounts] =
    Encoder.forProduct3("parsed", "dropped", "decodeFailureRate")(i => (i.parsed, i.dropped, i.decodeFailureRate))
  implicit val obsEncoder: Encoder[ObservationCounts] = deriveEncoder
  implicit val alertEncoder: Encoder[AlertCounts] = Encoder.forProduct6(
    "matched",
    "delivered",
    "suppressed",
    "suppressedByReason",
    "audience",
    "unaccounted",
  )(a => (a.matched, a.delivered, a.suppressed, a.suppressedByReason, a.audience, a.unaccounted))
  implicit val encoder: Encoder[RunView] = deriveEncoder
}
