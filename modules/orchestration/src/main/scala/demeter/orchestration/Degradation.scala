package demeter.orchestration

import demeter.foundations.{DealWatchError, SourceName}

/** Spec 08.2 — keep the service useful when a source breaks.
  *
  * Undocumented endpoints fail; the question is whether the run degrades
  * gracefully or dies. This turns error kinds into source-level decisions. The
  * single most important case is BotWall on Flipp — the "they added auth" event
  * — which must switch to the fallback source plus alert the operator, never
  * crash and never retry in a tight loop.
  */
sealed abstract class Degradation extends Product with Serializable

object Degradation {

  /** Try the configured fallback FlyerSource, and tell the operator. */
  case object UseFallbackAndAlert extends Degradation

  /** No fallback available: finish as a partial run, and tell the operator. */
  case object PartialRunAndAlert extends Degradation

  /** Carry on without this source's contribution (enrichment is advisory). */
  case object ContinueWithout extends Degradation

  /** Stop: a run that cannot persist cannot be trusted. */
  case object FailRun extends Degradation
}

object DegradationPolicy {

  /** @param fallbackAvailable whether an Apify (or other) fallback FlyerSource is configured
    * @param essential true for the flyer source and the store; false for enrichment
    */
  def decide(error: DealWatchError, fallbackAvailable: Boolean, essential: Boolean): Degradation =
    error match {
      // The store is the one thing we never degrade past: retries are the HTTP
      // policy's job, and a run that still can't persist must fail loudly.
      case _: DealWatchError.StoreUnavailable => Degradation.FailRun

      case _: DealWatchError.BotWall if essential =>
        if (fallbackAvailable) Degradation.UseFallbackAndAlert else Degradation.PartialRunAndAlert

      case _ if !essential => Degradation.ContinueWithout

      // repeated 5xx / timeouts past the retry budget, and 4xx: degrade the
      // source for this run and continue with whatever was already fetched
      case _ => Degradation.PartialRunAndAlert
    }

  /** Which failures should page the operator (00.5 + 08.2). */
  def needsOperatorAlert(error: DealWatchError, essential: Boolean): Boolean =
    error.operatorAttention || (essential && !error.retriable) || decide(error, fallbackAvailable = false, essential) == Degradation.FailRun

  def degradedEntry(source: SourceName, error: DealWatchError): DegradedSource = DegradedSource(source, error)
}
