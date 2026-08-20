package demeter.pricehistory

import java.nio.file.{Files, Path}
import java.time.{Instant, LocalDate, ZoneOffset}

import scala.jdk.CollectionConverters._
import scala.util.Try

import cats.effect.kernel.Sync
import cats.syntax.all._
import demeter.foundations._

/** Spec 07.1 — seed price history from Jacob Filipp's open Project Hammer
  * dataset so "is this a good deal?" has a baseline from day one, before our
  * own polling has accumulated weeks of data.
  *
  * A one-off / periodic batch loader, NOT part of the daily run. Rows are
  * stamped with Hammer provenance so baseline history is never confused with
  * first-party observations, and vendors whose matching Hammer itself marks
  * unreliable are flagged lower-trust so 07.2 weighs them down.
  */
final case class LoadReport(products: Int, priceRows: Int, skipped: Int)

final case class HammerRow(
    productId: String,
    vendor: String,
    productName: String,
    currentPrice: Option[Money],
    oldPrice: Option[Money],
    observedAt: Instant,
)

trait HammerLoader[F[_]] {
  def load(productCsv: Path, rawCsv: Path): F[Either[DealWatchError, LoadReport]]
}

object HammerLoader {

  /** Hammer vendors mapped to our merchant ids where they overlap. */
  val DefaultVendorMap: Map[String, MerchantId] = Map(
    "Metro"          -> MerchantId(2269),
    "Voila"          -> MerchantId(4592),
    "Loblaws"        -> MerchantId(1057),
    "NoFrills"       -> MerchantId(1052),
    "TandT"          -> MerchantId(2298),
    "Walmart"        -> MerchantId(234),
    "SaveOnFoods"    -> MerchantId(1054),
    "Galleria"       -> MerchantId(6853),
  )

  /** Vendors Hammer's own docs warn are fuzzy-matched (UPC/id unreliable):
    * loaded, but weighted down by 07.2 via HammerFuzzy provenance.
    */
  val FuzzyMatchedVendors: Set[String] = Set("Loblaws", "NoFrills", "TandT", "Voila")

  def provenanceFor(vendor: String): Provenance =
    if (FuzzyMatchedVendors.contains(vendor)) Provenance.HammerFuzzy else Provenance.Hammer

  def merchantFor(vendor: String, vendorMap: Map[String, MerchantId] = DefaultVendorMap): Option[MerchantId] =
    vendorMap.get(vendor.trim)

  /** Minimal CSV split honouring double-quoted fields (Hammer product names contain commas). */
  def splitCsvLine(line: String): List[String] = {
    val out   = List.newBuilder[String]
    val field = new StringBuilder
    var inQuotes = false
    var i = 0
    while (i < line.length) {
      val c = line.charAt(i)
      if (c == '"') {
        if (inQuotes && i + 1 < line.length && line.charAt(i + 1) == '"') { field.append('"'); i += 1 }
        else inQuotes = !inQuotes
      } else if (c == ',' && !inQuotes) { out += field.toString; field.clear() }
      else field.append(c)
      i += 1
    }
    out += field.toString
    out.result()
  }

  def parseDate(s: String): Option[Instant] =
    Try(LocalDate.parse(s.trim).atStartOfDay(ZoneOffset.UTC).toInstant).toOption
      .orElse(Try(Instant.parse(s.trim)).toOption)

  def parseMoney(s: String): Option[Money] = {
    val cleaned = s.trim.replace("$", "").replace(",", "")
    if (cleaned.isEmpty) None else Money.fromDecimal(cleaned).toOption
  }
}

/** Reads the two-file (product + raw) form, joining on product id. */
final class CsvHammerLoader[F[_]](
    vendorMap: Map[String, MerchantId] = HammerLoader.DefaultVendorMap,
    onRow: (HammerRow, MerchantId, Provenance) => F[Unit],
)(implicit F: Sync[F])
    extends HammerLoader[F] {

  def load(productCsv: Path, rawCsv: Path): F[Either[DealWatchError, LoadReport]] =
    F.delay {
      val products = readCsv(productCsv)
      val raws     = readCsv(rawCsv)

      // product dimension: id -> (vendor, name)
      val dimension = products.flatMap { row =>
        for {
          id     <- row.get("id").orElse(row.get("product_id"))
          vendor <- row.get("vendor")
          name   <- row.get("product_name").orElse(row.get("name"))
        } yield id -> (vendor, name)
      }.toMap

      (dimension, raws)
    }.flatMap { case (dimension, raws) =>
      raws
        .foldLeftM((0, 0)) { case ((loaded, skipped), row) =>
          val parsed = for {
            id                <- row.get("product_id").orElse(row.get("id"))
            (vendor, name)    <- dimension.get(id)
            merchant          <- HammerLoader.merchantFor(vendor, vendorMap)
            at                <- row.get("nowtime").orElse(row.get("date")).flatMap(HammerLoader.parseDate)
          } yield HammerRow(
            productId = id,
            vendor = vendor,
            productName = name,
            currentPrice = row.get("current_price").flatMap(HammerLoader.parseMoney),
            oldPrice = row.get("old_price").flatMap(HammerLoader.parseMoney),
            observedAt = at,
          ) -> merchant

          parsed match {
            case Some((hammerRow, merchant)) =>
              onRow(hammerRow, merchant, HammerLoader.provenanceFor(hammerRow.vendor)).as((loaded + 1, skipped))
            case None => F.pure((loaded, skipped + 1))
          }
        }
        .map { case (loaded, skipped) =>
          Right(LoadReport(products = dimension.size, priceRows = loaded, skipped = skipped)): Either[DealWatchError, LoadReport]
        }
    }.handleError(e => Left(DealWatchError.InvalidDomain("hammer", e.toString)))

  private def readCsv(path: Path): List[Map[String, String]] = {
    val lines = Files.readAllLines(path).asScala.toList.filter(_.trim.nonEmpty)
    lines match {
      case Nil => Nil
      case header :: rows =>
        val columns = HammerLoader.splitCsvLine(header).map(_.trim)
        rows.map(r => columns.zip(HammerLoader.splitCsvLine(r).map(_.trim)).toMap.filter(_._2.nonEmpty))
    }
  }
}
