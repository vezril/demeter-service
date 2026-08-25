package demeter.insight

import java.time.{Duration, Instant}

import cats.effect.kernel.{Clock, MonadCancelThrow}
import cats.syntax.all._
import doobie.Transactor

import demeter.foundations.ProductKey
import demeter.persistence.DoobieObservationStore
import demeter.pricehistory.{DealScorer, HistoryPoint, RollingStats}

/** Per-product price history.
  *
  * Reads through persistence's typed store and computes through pricehistory,
  * rather than reimplementing either. The reuse is the point: the median this
  * serves has to be the median an alert quotes, and two implementations of a
  * weighted median are two chances to disagree about what "below usual" means.
  */
trait HistoryQueries[F[_]] {
  def forProduct(key: ProductKey, window: Duration): F[HistoryView]
}

final class DbHistoryQueries[F[_]: MonadCancelThrow: Clock](xa: Transactor[F]) extends HistoryQueries[F] {

  private val store = new DoobieObservationStore[F](xa)

  def forProduct(key: ProductKey, window: Duration): F[HistoryView] =
    Clock[F].realTime.map(d => Instant.ofEpochMilli(d.toMillis)).flatMap { now =>
      store.observationsFor(key, now.minus(window)).map { observations =>
        val points = observations.map(HistoryPoint(_))
        val stats  = RollingStats.rollingStats(key, points, window, now)

        // The verdict describes the MOST RECENT observation against the rest of
        // the window, which is the question a reader is actually asking: is what
        // I am looking at now a good price?
        val verdict = observations.sortBy(_.observedAt).lastOption.map { latest =>
          DealScorer.scoreDeal(
            latest,
            RollingStats.rollingStats(key, points.filterNot(_.observation.sameRecordAs(latest)), window, now),
            None,
          )
        }

        HistoryView(
          productKey = key.value,
          windowDays = window.toDays,
          points = observations.sortBy(_.observedAt).map(HistoryView.pointOf),
          stats = HistoryView.statsOf(stats),
          verdict = verdict.map(HistoryView.verdictLabel),
        )
      }
    }
}
