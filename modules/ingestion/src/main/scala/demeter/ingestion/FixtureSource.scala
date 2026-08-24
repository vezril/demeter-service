package demeter.ingestion

import java.nio.file.{Files, Path}
import java.time.Instant

import cats.effect.kernel.Sync
import cats.syntax.all._
import demeter.foundations._

/** Spec 01.1 — the captured-fixtures FlyerSource: drives tests (and the 08.1
  * end-to-end suite) with zero network. Capabilities are configurable so the
  * "unsupported capability fails cleanly, no network call" contract is
  * exercised against a real implementation.
  */
final class FixtureSource[F[_]](
    flyersFixture: Path,
    itemsFixture: Path,
    override val capabilities: Set[Capability] = Set(Capability.Flyers, Capability.Items, Capability.Search),
)(implicit F: Sync[F])
    extends FlyerSource[F] {

  val name: SourceName = SourceName("fixture")

  def flyers(postal: PostalCode, locale: Locale): F[Either[DealWatchError, RawFlyerListing]] =
    guarded(Capability.Flyers) {
      read(flyersFixture).map { raw =>
        for {
          json   <- FlippDecoders.parseJson(name.value, raw.bytes)
          parsed <- FlippDecoders.decodeListing(name.value, json)
        } yield RawFlyerListing(raw, parsed.flyers, parsed.merchants, parsed.dropped)
      }
    }

  def items(flyerId: FlyerId, postal: PostalCode, locale: Locale): F[Either[DealWatchError, RawFlyerItems]] =
    guarded(Capability.Items) {
      read(itemsFixture).map { raw =>
        for {
          json   <- FlippDecoders.parseJson(name.value, raw.bytes)
          parsed <- FlippDecoders.decodeItems(name.value, json)
        } yield RawFlyerItems(raw, parsed.items.filter(_.flyerId == flyerId), parsed.dropped)
      }
    }

  def search(term: String, postal: PostalCode, locale: Locale): F[Either[DealWatchError, RawSearchResult]] =
    guarded(Capability.Search) {
      read(itemsFixture).map { raw =>
        for {
          json   <- FlippDecoders.parseJson(name.value, raw.bytes)
          parsed <- FlippDecoders.decodeSearch(name.value, json)
        } yield RawSearchResult(
          raw,
          parsed.flyerItems,
          parsed.ecomItems,
          parsed.merchants,
          parsed.normalizedQuery,
          parsed.dropped,
        )
      }
    }

  private def guarded[A](cap: Capability)(body: F[Either[DealWatchError, A]]): F[Either[DealWatchError, A]] =
    if (capabilities(cap)) body
    else F.pure(Left(DealWatchError.Unsupported(name.value, cap.toString)))

  private def read(path: Path): F[RawResponse] =
    F.delay(Files.readAllBytes(path))
      .map(RawResponse(_, "application/json", Instant.EPOCH, s"file://$path"))
}
