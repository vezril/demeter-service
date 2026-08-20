package demeter.orchestration

import java.time.Instant

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
    watchlist: List[WatchItem],
    alertState: Ref[F, Map[AlertKey, AlertRecord]],
    merchantNames: Ref[F, Map[MerchantId, String]],
)(implicit F: Concurrent[F], C: Clock[F]) {

  def run: F[RunReport] =
    for {
      startedAt <- C.realTime
      report    <- Ref.of[F, RunReport](RunReport())
      _         <- listAndProcess(report)
      endedAt   <- C.realTime
      finished  <- report.updateAndGet(r => r.copy(elapsed = Some(endedAt - startedAt)))
    } yield finished

  private def listAndProcess(report: Ref[F, RunReport]): F[Unit] =
    C.realTime.map(d => Instant.ofEpochMilli(d.toMillis)).flatMap { now =>
      fetchListing(report).flatMap {
        case None => F.unit // degraded; the report already says so
        case Some(listing) =>
          for {
            _        <- observations.upsertMerchants(listing.merchants)
            _        <- merchantNames.update(_ ++ listing.merchants.map(m => m.id -> m.name).toMap)
            _        <- report.update(_.copy(flyersListed = listing.flyers.size))
            toFetch  <- ledger.selectToFetch(listing.flyers, now)
            _        <- report.update(_.copy(flyersSelected = toFetch.size))
            _        <- fetchFlyers(toFetch, now, report)
            _        <- matchAndAlert(now, report)
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
          r.copy(degraded = r.degraded :+ DegradedSource(source.name, error), failures = r.failures :+ error, partial = true)
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
                val observed = items.items.map(ObservationAssembler.assemble(_, now, config.locale))
                observations.saveAll(observed, rawId).flatMap {
                  case Left(error) =>
                    report.update(r => r.copy(flyersFailed = r.flyersFailed + 1, failures = r.failures :+ error))
                  case Right(saved) =>
                    ledger.markFetched(flyer.id, (flyer.validFrom, flyer.validTo), rawId) *>
                      report.update(r =>
                        r.copy(
                          flyersFetched = r.flyersFetched + 1,
                          itemsParsed = r.itemsParsed + items.items.size,
                          itemsDropped = r.itemsDropped + items.dropped,
                          observationsInserted = r.observationsInserted + saved.inserted,
                          observationsSkipped = r.observationsSkipped + saved.skippedDuplicate,
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
    observations
      .currentObservations(now)
      .compile
      .toList
      .flatMap { active =>
        val grouped = watchlist.filter(_.active).map { watch =>
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
      stats = RollingStats.rollingStats(
        m.observation.productKey,
        history.map(HistoryPoint(_)),
        config.history.window,
        now,
      )
      verdict = DealScorer.scoreDeal(m.observation, stats, None, config.deals)
      _ <- DealDecision.decide(m, verdict) match {
        case AlertDecision.Suppress(_) => report.update(r => r.copy(alertsSuppressed = r.alertsSuppressed + 1))
        case AlertDecision.Alert(deal) => deliverIfNew(deal, now, report)
      }
    } yield ()

  private def deliverIfNew(deal: Deal, now: Instant, report: Ref[F, RunReport]): F[Unit] =
    alertState.get.flatMap { sent =>
      if (!AlertDedup.isNew(deal, sent)) report.update(r => r.copy(alertsSuppressed = r.alertsSuppressed + 1))
      else
        merchantNames.get.flatMap { names =>
          val merchant = names.getOrElse(deal.observation.merchantId, s"merchant ${deal.observation.merchantId.value}")
          sink.deliver(Alert.of(deal, merchant, config.locale)).flatMap {
            case Right(_) =>
              alertState.update(_ + (AlertDedup.keyOf(deal) -> AlertDedup.record(deal, now))) *>
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
      config: Config,
      watchlist: List[WatchItem],
  ): F[DailyRun[F]] =
    for {
      alerts    <- Ref.of[F, Map[AlertKey, AlertRecord]](Map.empty)
      merchants <- Ref.of[F, Map[MerchantId, String]](Map.empty)
    } yield new DailyRun(source, fallbackSource, rawStore, observations, ledger, sink, config, watchlist, alerts, merchants)
}
