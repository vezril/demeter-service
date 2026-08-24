package demeter.persistence

import java.security.MessageDigest

import scala.concurrent.duration._

import cats.effect.kernel.MonadCancelThrow
import cats.syntax.all._
import demeter.foundations._
import doobie._
import doobie.implicits._
import doobie.postgres.implicits._

/** Spec 03.2 — archive the exact bytes of every upstream fetch before anything
  * parses them. The foundation of the replayable/back-fillable property: fix a
  * decoder, replay the archive, no re-fetching.
  */
final case class RawResponseId(value: Long) extends AnyVal

sealed abstract class ResponseKind(val dbValue: String) extends Product with Serializable

object ResponseKind {
  case object Flyers     extends ResponseKind("flyers")
  case object FlyerItems extends ResponseKind("flyer_items")
  case object Search     extends ResponseKind("search")
}

trait RawResponseStore[F[_]] {
  def put(
      raw: RawResponse,
      source: SourceName,
      kind: ResponseKind,
      postal: PostalCode,
      locale: Locale,
  ): F[Either[DealWatchError, RawResponseId]]

  def get(id: RawResponseId): F[Either[DealWatchError, RawResponse]]

  def stream(source: SourceName, kind: ResponseKind): fs2.Stream[F, (RawResponseId, RawResponse)]
}

/** @param dedupWindow when Some, an identical body (same sha256/source/kind/
  *   postal/locale) stored within the window returns the existing id instead of
  *   duplicating. Off by default for safety (03.2).
  */
final class DoobieRawResponseStore[F[_]: MonadCancelThrow](
    xa: Transactor[F],
    dedupWindow: Option[FiniteDuration] = None,
) extends RawResponseStore[F] {
  import Codecs._

  def put(
      raw: RawResponse,
      source: SourceName,
      kind: ResponseKind,
      postal: PostalCode,
      locale: Locale,
  ): F[Either[DealWatchError, RawResponseId]] = {
    val sha = sha256(raw.bytes)

    val existing: ConnectionIO[Option[Long]] = dedupWindow match {
      case None => Option.empty[Long].pure[ConnectionIO]
      case Some(window) =>
        val cutoff = raw.fetchedAt.minusMillis(window.toMillis)
        sql"""SELECT id FROM raw_response
              WHERE source = ${source.value} AND kind = ${kind.dbValue}
                AND postal_code = ${postal.canonical} AND locale = $locale
                AND body_sha256 = $sha AND fetched_at >= $cutoff
              ORDER BY id DESC LIMIT 1""".query[Long].option
    }

    val insert: ConnectionIO[Long] =
      sql"""INSERT INTO raw_response (source, kind, url, postal_code, locale, fetched_at, content_type, body, body_sha256)
            VALUES (${source.value}, ${kind.dbValue}, ${raw.url}, ${postal.canonical}, $locale,
                    ${raw.fetchedAt}, ${raw.contentType}, ${raw.bytes}, $sha)""".update
        .withUniqueGeneratedKeys[Long]("id")

    existing
      .flatMap {
        case Some(id) => id.pure[ConnectionIO]
        case None     => insert
      }
      .transact(xa)
      .map(id => Right(RawResponseId(id)): Either[DealWatchError, RawResponseId])
      .recover { case e => Left(DealWatchError.StoreUnavailable(e.toString)) }
  }

  def get(id: RawResponseId): F[Either[DealWatchError, RawResponse]] =
    sql"""SELECT body, content_type, fetched_at, url FROM raw_response WHERE id = ${id.value}"""
      .query[(Array[Byte], String, java.time.Instant, String)]
      .option
      .transact(xa)
      .map {
        case Some((bytes, ct, at, url)) => Right(RawResponse(bytes, ct, at, url)): Either[DealWatchError, RawResponse]
        case None                       => Left(DealWatchError.StoreConflict("raw_response", id.value.toString))
      }
      .recover { case e => Left(DealWatchError.StoreUnavailable(e.toString)) }

  def stream(source: SourceName, kind: ResponseKind): fs2.Stream[F, (RawResponseId, RawResponse)] =
    sql"""SELECT id, body, content_type, fetched_at, url FROM raw_response
          WHERE source = ${source.value} AND kind = ${kind.dbValue} ORDER BY id"""
      .query[(Long, Array[Byte], String, java.time.Instant, String)]
      .stream
      .map { case (id, bytes, ct, at, url) => (RawResponseId(id), RawResponse(bytes, ct, at, url)) }
      .transact(xa)

  private def sha256(bytes: Array[Byte]): Array[Byte] =
    MessageDigest.getInstance("SHA-256").digest(bytes)
}
