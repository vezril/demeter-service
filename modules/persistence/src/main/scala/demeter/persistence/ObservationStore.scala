package demeter.persistence

import java.time.Instant

import cats.effect.kernel.MonadCancelThrow
import cats.syntax.all._
import demeter.foundations._
import doobie._
import doobie.implicits._
import doobie.postgres.implicits._

/** Spec 03.3 — idempotent persistence of normalized observations. Re-running
  * the same day's fetch after a crash must not double-count prices: the
  * ON CONFLICT DO NOTHING upsert on (product_key, flyer_id, observed_at) makes
  * a duplicate a counted no-op, never an error.
  */
final case class SaveReport(inserted: Int, skippedDuplicate: Int, failed: Int)

sealed abstract class SaveOutcome extends Product with Serializable

object SaveOutcome {
  case object Inserted         extends SaveOutcome
  case object SkippedDuplicate extends SaveOutcome
}

trait ObservationStore[F[_]] {
  def upsertMerchants(merchants: List[Merchant]): F[Either[DealWatchError, Unit]]
  def save(obs: PriceObservation, rawId: RawResponseId): F[Either[DealWatchError, SaveOutcome]]
  def saveAll(obs: List[PriceObservation], rawId: RawResponseId): F[Either[DealWatchError, SaveReport]]
  def observationsFor(key: ProductKey, since: Instant): F[List[PriceObservation]]
  def currentObservations(activeAt: Instant): fs2.Stream[F, PriceObservation]
  def currentObservationsFor(merchant: MerchantId, activeAt: Instant): fs2.Stream[F, PriceObservation]
}

final class DoobieObservationStore[F[_]: MonadCancelThrow](xa: Transactor[F]) extends ObservationStore[F] {
  import Codecs._

  def upsertMerchants(merchants: List[Merchant]): F[Either[DealWatchError, Unit]] =
    merchants
      .traverse_(m => sql"""INSERT INTO merchant (id, name) VALUES (${m.id.value}, ${m.name})
              ON CONFLICT (id) DO UPDATE SET name = EXCLUDED.name""".update.run)
      .transact(xa)
      .map(_ => Right(()): Either[DealWatchError, Unit])
      .recover { case e => Left(DealWatchError.StoreUnavailable(e.toString)) }

  def save(obs: PriceObservation, rawId: RawResponseId): F[Either[DealWatchError, SaveOutcome]] =
    saveOne(obs, rawId)
      .transact(xa)
      .map(o => Right(o): Either[DealWatchError, SaveOutcome])
      .recover { case e => Left(DealWatchError.StoreUnavailable(e.toString)) }

  /** Transactional per flyer batch: the batch's inserts commit or roll back together. */
  def saveAll(obs: List[PriceObservation], rawId: RawResponseId): F[Either[DealWatchError, SaveReport]] =
    obs
      .traverse(saveOne(_, rawId))
      .transact(xa)
      .map { outcomes =>
        Right(
          SaveReport(
            inserted = outcomes.count(_ == SaveOutcome.Inserted),
            skippedDuplicate = outcomes.count(_ == SaveOutcome.SkippedDuplicate),
            failed = 0,
          )
        ): Either[DealWatchError, SaveReport]
      }
      .recover { case e => Left(DealWatchError.StoreUnavailable(e.toString)) }

  private def saveOne(obs: PriceObservation, rawId: RawResponseId): ConnectionIO[SaveOutcome] =
    upsertProduct(obs) *>
      sql"""INSERT INTO price_observation
              (product_key, merchant_id, flyer_id, observed_at, raw_name, display_name_en, display_name_fr,
               effective_cents, price_basis, original_cents, size_qty, size_unit, pack_count,
               unit_cents, unit_basis, sale_text, valid_from, valid_to,
               price_confidence, match_confidence, raw_response_id)
            VALUES
              (${obs.productKey.value}, ${obs.merchantId.value}, ${obs.flyerId.value}, ${obs.observedAt},
               ${obs.rawName}, ${obs.name.en}, ${obs.name.fr},
               ${obs.effectivePrice.map(_.cents)}, ${obs.priceBasis}, ${obs.originalPrice.map(_.cents)},
               ${obs.size.map(_.quantity)}, ${obs.size.map(_.unit)}, ${obs.size.map(_.packCount)},
               ${obs.unitPrice.map(_.price.cents)}, ${obs.unitPrice.map(_.per)}, ${obs.saleText},
               ${obs.validFrom}, ${obs.validTo}, ${obs.priceConfidence}, ${obs.matchConfidence}, ${rawId.value})
            ON CONFLICT (product_key, flyer_id, observed_at) DO NOTHING""".update.run
        .map(n => if (n > 0) SaveOutcome.Inserted else SaveOutcome.SkippedDuplicate)

  /** The product dimension row rides along with every observation save (03.3). */
  private def upsertProduct(obs: PriceObservation): ConnectionIO[Unit] =
    sql"""INSERT INTO product (key, merchant_id, display_name_en, display_name_fr, size_qty, size_unit, pack_count, first_seen_at)
          VALUES (${obs.productKey.value}, ${obs.merchantId.value}, ${obs.name.en}, ${obs.name.fr},
                  ${obs.size.map(_.quantity)}, ${obs.size.map(_.unit)}, ${obs.size.map(_.packCount)}, ${obs.observedAt})
          ON CONFLICT (key) DO UPDATE SET
            display_name_en = COALESCE(EXCLUDED.display_name_en, product.display_name_en),
            display_name_fr = COALESCE(EXCLUDED.display_name_fr, product.display_name_fr)""".update.run.void

  def observationsFor(key: ProductKey, since: Instant): F[List[PriceObservation]] =
    (selectFragment ++ sql" WHERE product_key = ${key.value} AND observed_at >= $since ORDER BY observed_at DESC")
      .query[ObsRow]
      .to[List]
      .map(_.map(_.toDomain))
      .transact(xa)

  def currentObservations(activeAt: Instant): fs2.Stream[F, PriceObservation] =
    (selectFragment ++ sql" WHERE valid_from <= $activeAt AND valid_to >= $activeAt ORDER BY id")
      .query[ObsRow]
      .stream
      .map(_.toDomain)
      .transact(xa)

  def currentObservationsFor(merchant: MerchantId, activeAt: Instant): fs2.Stream[F, PriceObservation] =
    (selectFragment ++
      sql" WHERE merchant_id = ${merchant.value} AND valid_from <= $activeAt AND valid_to >= $activeAt ORDER BY id")
      .query[ObsRow]
      .stream
      .map(_.toDomain)
      .transact(xa)

  private val selectFragment =
    sql"""SELECT product_key, merchant_id, flyer_id, observed_at, raw_name, display_name_en, display_name_fr,
                 effective_cents, price_basis, original_cents, size_qty, size_unit, pack_count,
                 unit_cents, unit_basis, sale_text, valid_from, valid_to,
                 price_confidence, match_confidence, id
          FROM price_observation"""

  private final case class ObsRow(
      productKey: String,
      merchantId: Int,
      flyerId: Long,
      observedAt: Instant,
      rawName: String,
      nameEn: Option[String],
      nameFr: Option[String],
      effectiveCents: Option[Long],
      priceBasis: PriceBasis,
      originalCents: Option[Long],
      sizeQty: Option[BigDecimal],
      sizeUnit: Option[StdUnit],
      packCount: Option[Int],
      unitCents: Option[Long],
      unitBasis: Option[StdUnit],
      saleText: Option[String],
      validFrom: Instant,
      validTo: Instant,
      priceConfidence: Confidence,
      matchConfidence: Confidence,
      id: Long,
  ) {
    def toDomain: PriceObservation =
      PriceObservation(
        productKey = ProductKey(productKey),
        merchantId = MerchantId(merchantId),
        flyerId = FlyerId(flyerId),
        observedAt = observedAt,
        name = BilingualText(fr = nameFr, en = nameEn),
        rawName = rawName,
        effectivePrice = effectiveCents.map(Money.cents(_)),
        priceBasis = priceBasis,
        originalPrice = originalCents.map(Money.cents(_)),
        size = (sizeQty, sizeUnit, packCount).mapN(Size.apply),
        unitPrice = (unitCents.map(Money.cents(_)), unitBasis).mapN(UnitPrice.apply),
        saleText = saleText,
        validFrom = validFrom,
        validTo = validTo,
        priceConfidence = priceConfidence,
        matchConfidence = matchConfidence,
      )
  }
}
