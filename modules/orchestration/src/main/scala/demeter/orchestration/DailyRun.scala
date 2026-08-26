package demeter.orchestration

import java.time.Instant

import scala.util.Try

import cats.effect.kernel.{Clock, Concurrent, Ref}
import cats.syntax.all._
import demeter.alerting._
import demeter.foundations._
import demeter.ingestion._
import demeter.normalization.ObservationAssembler
import demeter.persistence._
import demeter.pricehistory._
import demeter.watchlist._

/** Spec 08.1 — the scheduled job that ties every context together into one
  * idempotent daily pass.
  *
  * Pinned sequence: list flyers -> diff against the ledger -> fetch only new or
  * changed flyers (archiving raw bytes BEFORE trusting any parse) -> normalize
  * and persist -> match the watchlist -> score against history -> decide ->
  * dedup -> deliver.
  *
  * Never throws: per-flyer failures land in the report rather than propagating,
  * so one bad flyer out of twelve costs you that flyer, not the run. Every
  * store is idempotent (03.3/03.4), so a crash at any step is safe to re-run.
  */
final class DailyRun[F[_]](
    source: FlyerSource[F],
    fallbackSource: Option[FlyerSource[F]],
    rawStore: RawResponseStore[F],
    observations: ObservationStore[F],
    ledger: FlyerLedger[F],
    sink: AlertSink[F],
    config: Config,
    /** Loaded at the START OF EACH RUN, not once at boot.
      *
      * Held as an effect rather than a list so a watch added or paused between
      * runs takes effect on the next one. Loading once at construction meant a
      * change to the watchlist did nothing until someone restarted the pod --
      * survivable while the only way to edit it was psql, and untenable now that
      * there is a UI whose whole purpose is editing it.
      */
    watchlist: F[List[WatchItem]],
    alertLedger: AlertLedger[F],
    alertState: Ref[F, Map[AlertKey, AlertRecord]],
    merchantNames: Ref[F, Map[MerchantId, String]],
    /** Seam, so the containment below can be tested for ANY failing item rather
      * than whichever input happens to throw today. Two different inputs have
      * already had this bug -- a size that normalized to zero, and a pack count
      * too large for an Int -- and both were fixed, which would have quietly
      * disarmed a test that relied on either.
      */
    assembleItem: (FlyerItem, Instant, Locale) => PriceObservation = (i: FlyerItem, at: Instant, l: Locale) =>
      ObservationAssembler.assemble(i, at, l),
)(implicit F: Concurrent[F], C: Clock[F]) {

  def run: F[RunReport] =
    for {
      startedAt <- C.realTime
      report    <- Ref.of[F, RunReport](RunReport())
      // Rehydrate what has already been alerted BEFORE matching. Without this a
      // restart re-alerts every deal still inside its flyer window (05.2).
      _       <- rehydrateAlertState(startedAt)
      _       <- listAndProcess(report)
      endedAt <- C.realTime
      // Asked once, after delivery, and only when something was actually sent:
      // an empty audience matters because alerts went into it, and a run that
      // alerted nothing has nothing to be unheard.
      delivered <- report.get.map(_.alertsDelivered)
      audience  <- if (delivered > 0) sink.audience else F.pure(Option.empty[Int])
      finished  <- report.updateAndGet(r => r.copy(elapsed = Some(endedAt - startedAt), alertAudience = audience))
    } yield finished

  private def rehydrateAlertState(startedAt: scala.concurrent.duration.FiniteDuration): F[Unit] = {
    val now = Instant.ofEpochMilli(startedAt.toMillis)
    alertLedger.openAt(now).flatMap(prior => alertState.set(prior))
  }

  private def listAndProcess(report: Ref[F, RunReport]): F[Unit] =
    C.realTime.map(d => Instant.ofEpochMilli(d.toMillis)).flatMap { now =>
      fetchListing(report).flatMap {
        case None => F.unit // degraded; the report already says so
        case Some(listing) =>
          for {
            _       <- observations.upsertMerchants(listing.merchants)
            _       <- merchantNames.update(_ ++ listing.merchants.map(m => m.id -> m.name).toMap)
            _       <- report.update(_.copy(flyersListed = listing.flyers.size))
            toFetch <- ledger.selectToFetch(listing.flyers, now)
            _       <- report.update(_.copy(flyersSelected = toFetch.size))
            _       <- fetchFlyers(toFetch, now, report)
            _       <- matchAndAlert(now, report)
          } yield ()
      }
    }

  /** Step 1 — the listing, with the BotWall -> fallback switch (08.2). */
  private def fetchListing(report: Ref[F, RunReport]): F[Option[RawFlyerListing]] =
    source.flyers(config.postalCode, config.locale).flatMap {
      case Right(listing) => F.pure(Some(listing))
      case Left(error) =>
        val decision = DegradationPolicy.decide(error, fallbackSource.isDefined, essential = true)
        report.update(r =>
          r.copy(
            degraded = r.degraded :+ DegradedSource(source.name, error),
            failures = r.failures :+ error,
          )
        ) *> {
          decision match {
            case Degradation.UseFallbackAndAlert =>
              fallbackSource.traverse(_.flyers(config.postalCode, config.locale)).map(_.flatMap(_.toOption))
            case _ => F.pure(None)
          }
        }
    }

  /** Step 2 — bounded-concurrency fan-out; one failing flyer never sinks the run. */
  private def fetchFlyers(flyers: List[Flyer], now: Instant, report: Ref[F, RunReport]): F[Unit] =
    Concurrent[F].parTraverseN(config.run.flyerConcurrency.max(1))(flyers)(processFlyer(_, now, report)).void

  private def processFlyer(flyer: Flyer, now: Instant, report: Ref[F, RunReport]): F[Unit] =
    source
      .items(flyer.id, config.postalCode, config.locale)
      .flatMap {
        case Left(error) =>
          report.update(r => r.copy(flyersFailed = r.flyersFailed + 1, failures = r.failures :+ error))
        case Right(items) =>
          for {
            // archive the raw bytes BEFORE anything trusts the parse (03.2)
            archived <- rawStore.put(items.raw, source.name, ResponseKind.FlyerItems, config.postalCode, config.locale)
            _ <- archived match {
              case Left(error) =>
                report.update(r => r.copy(flyersFailed = r.flyersFailed + 1, failures = r.failures :+ error))
              case Right(rawId) =>
                // Per-flyer responses carry no merchant — it belongs to the
                // flyer, not the item — so resolve it here, where the flyer is
                // authoritative. Without this the product key (02.7) would be
                // built on merchant 0 and every product would collide.
                val owned = items.items.map(i => i.copy(merchantId = flyer.merchantId))
                // Assemble each item on its own.
                //
                // This was `owned.map(assemble)`, and a strict map throws as a
                // whole: on 2026-08-26 one item with an unparseable size threw
                // out of here, was caught by the flyer-level handler below, and
                // took its entire flyer with it -- three times over, roughly 410
                // observations. The blast radius of a bad item must be that
                // item. Everything else on the flyer is still good data, and
                // flyers expire, so what is not stored today is gone.
                val (unassembled, observed) = owned.partitionMap { i =>
                  Try(assembleItem(i, now, config.locale)).toEither.left.map(i.rawName -> _)
                }
                observations.saveAll(observed, rawId).flatMap {
                  case Left(error) =>
                    report.update(r => r.copy(flyersFailed = r.flyersFailed + 1, failures = r.failures :+ error))
                  case Right(saved) =>
                    // An item that would not assemble is DROPPED, not parsed --
                    // counting it as parsed would leave decodeFailureRate blind
                    // to exactly the kind of systemic breakage it exists to
                    // catch.
                    // One summary entry per flyer, not one per item: a flyer
                    // that breaks on every item would otherwise bury the rest
                    // of the report, and the count plus an example is what
                    // makes it diagnosable.
                    val dropped =
                      if (unassembled.isEmpty) Nil
                      else {
                        val (rawName, cause) = unassembled.head
                        List[DealWatchError](
                          DealWatchError.Decode(
                            source.name.value,
                            s"flyer/${flyer.id.value}",
                            s"${unassembled.size} item(s) would not assemble and were dropped; first: '$rawName' ($cause)",
                          )
                        )
                      }
                    ledger.markFetched(flyer.id, (flyer.validFrom, flyer.validTo), rawId) *>
                      report.update(r =>
                        r.copy(
                          flyersFetched = r.flyersFetched + 1,
                          itemsParsed = r.itemsParsed + observed.size,
                          itemsDropped = r.itemsDropped + items.dropped + unassembled.size,
                          observationsInserted = r.observationsInserted + saved.inserted,
                          observationsSkipped = r.observationsSkipped + saved.skippedDuplicate,
                          failures = r.failures ++ dropped,
                        )
                      )
                }
            }
          } yield ()
      }
      // an unexpected throwable from one flyer is contained, never fatal
      .handleErrorWith(e =>
        report.update(r =>
          r.copy(
            flyersFailed = r.flyersFailed + 1,
            failures = r.failures :+ DealWatchError.Transport(s"flyer/${flyer.id.value}", e.toString),
          )
        )
      )

  /** Steps 4-6 — match what's active now, score it, decide, dedup, deliver. */
  private def matchAndAlert(now: Instant, report: Ref[F, RunReport]): F[Unit] =
    (observations.currentObservations(now).compile.toList, watchlist).tupled
      .flatMap { case (active, watches) =>
        val grouped = watches.filter(_.active).map { watch =>
          val hits = active.flatMap(o => Matcher.matchItem(watch, o).map(o -> _))
          watch -> MatchScore.scoreGroup(watch, hits, config.scoring)
        }
        val allMatches = grouped.flatMap(_._2)
        report.update(_.copy(matches = allMatches.size)) *>
          allMatches.sortBy(-_.score.combined).traverse_(considerMatch(_, now, report))
      }

  private def considerMatch(m: Match, now: Instant, report: Ref[F, RunReport]): F[Unit] =
    for {
      history <- observations.observationsFor(m.observation.productKey, now.minusMillis(config.history.window.toMillis))
      // the row being judged must not be part of the baseline judging it
      baseline = RollingStats.baselineFor(m.observation, history.map(HistoryPoint(_)))
      stats = RollingStats.rollingStats(
        m.observation.productKey,
        baseline,
        config.history.window,
        now,
      )
      verdict = DealScorer.scoreDeal(m.observation, stats, None, config.deals)
      _ <- DealDecision.decide(m, verdict) match {
        case AlertDecision.Suppress(why) => report.update(_.withSuppression(why.message))
        case AlertDecision.Alert(deal)   => deliverIfNew(deal, now, report)
      }
    } yield ()

  private def deliverIfNew(deal: Deal, now: Instant, report: Ref[F, RunReport]): F[Unit] =
    alertState.get.flatMap { sent =>
      if (!AlertDedup.isNew(deal, sent)) report.update(_.withSuppression("already alerted this window"))
      else
        merchantNames.get.flatMap { names =>
          val merchant = names.getOrElse(deal.observation.merchantId, s"merchant ${deal.observation.merchantId.value}")
          sink.deliver(Alert.of(deal, merchant, config.locale)).flatMap {
            case Right(_) =>
              val entry = AlertDedup.record(deal, now)
              // Write through to the ledger so the suppression survives a
              // restart. A ledger write failure is recorded but does not fail
              // the run — the alert genuinely went out, and the worst case is
              // one duplicate later, which beats losing the run over bookkeeping.
              alertState.update(_ + (entry.key -> entry)) *>
                alertLedger.record(entry).flatMap {
                  case Right(_)    => F.unit
                  case Left(error) => report.update(r => r.copy(failures = r.failures :+ error))
                } *>
                report.update(r => r.copy(alertsDelivered = r.alertsDelivered + 1))
            case Left(error) =>
              report.update(r => r.copy(failures = r.failures :+ error))
          }
        }
    }
}

object DailyRun {

  def create[F[_]: Concurrent: Clock](
      source: FlyerSource[F],
      fallbackSource: Option[FlyerSource[F]],
      rawStore: RawResponseStore[F],
      observations: ObservationStore[F],
      ledger: FlyerLedger[F],
      sink: AlertSink[F],
      alertLedger: AlertLedger[F],
      config: Config,
      /** An effect, re-evaluated each run — see the field's comment. Tests that
        * want a fixed list can pass `F.pure(list)`.
        */
      watchlist: F[List[WatchItem]],
      assembleItem: (FlyerItem, Instant, Locale) => PriceObservation = (i: FlyerItem, at: Instant, l: Locale) =>
        ObservationAssembler.assemble(i, at, l),
  ): F[DailyRun[F]] =
    for {
      alerts    <- Ref.of[F, Map[AlertKey, AlertRecord]](Map.empty)
      merchants <- Ref.of[F, Map[MerchantId, String]](Map.empty)
    } yield new DailyRun(
      source,
      fallbackSource,
      rawStore,
      observations,
      ledger,
      sink,
      config,
      watchlist,
      alertLedger,
      alerts,
      merchants,
      assembleItem,
    )
}
