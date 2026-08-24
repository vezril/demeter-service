package demeter.pricehistory

import java.nio.file.{Files, Path}

import cats.effect.IO
import cats.effect.kernel.Ref
import cats.effect.unsafe.implicits.global
import demeter.foundations.{MerchantId, Money}
import org.scalatest.funsuite.AnyFunSuite

/** Spec 07.1 — load Project Hammer as baseline history. Tags: @boundary (CSV IO). */
final class HammerLoaderSpec extends AnyFunSuite {

  private def tempCsv(name: String, content: String): Path = {
    val p = Files.createTempFile(name, ".csv")
    Files.write(p, content.getBytes("UTF-8"))
    p.toFile.deleteOnExit()
    p
  }

  private val productCsv = tempCsv(
    "product",
    """id,vendor,product_name
      |1,Metro,"Natrel Milk, 4 L"
      |2,Loblaws,No Name Butter 454 g
      |3,UnknownMart,Mystery Item
      |""".stripMargin,
  )

  private val rawCsv = tempCsv(
    "raw",
    """product_id,current_price,old_price,nowtime
      |1,4.99,5.49,2026-07-20
      |2,3.99,,2026-07-20
      |3,9.99,,2026-07-20
      |""".stripMargin,
  )

  private def loadAll(): (LoadReport, List[(HammerRow, MerchantId, Provenance)]) = {
    val seen          = Ref.of[IO, List[(HammerRow, MerchantId, Provenance)]](Nil).unsafeRunSync()
    val loader        = new CsvHammerLoader[IO](onRow = (r, m, p) => seen.update(_ :+ ((r, m, p))))
    val Right(report) = loader.load(productCsv, rawCsv).unsafeRunSync()
    (report, seen.get.unsafeRunSync())
  }

  test("product and raw files join into price history rows carrying current and old price") {
    val (report, rows) = loadAll()
    assert(report.products == 3)
    assert(report.priceRows == 2) // UnknownMart has no merchant mapping
    assert(report.skipped == 1)

    val metro = rows.find(_._1.vendor == "Metro").get._1
    assert(metro.currentPrice.contains(Money.cents(499)))
    assert(metro.oldPrice.contains(Money.cents(549)))
    assert(metro.productName == "Natrel Milk, 4 L") // quoted comma survives the split
  }

  test("vendor names map to our merchant ids where they overlap") {
    val (_, rows) = loadAll()
    assert(rows.find(_._1.vendor == "Metro").get._2 == MerchantId(2269))
    assert(HammerLoader.merchantFor("UnknownMart").isEmpty)
  }

  test("fuzzy-matched vendors are loaded but flagged lower-trust") {
    val (_, rows) = loadAll()
    assert(rows.find(_._1.vendor == "Metro").get._3 == Provenance.Hammer)
    assert(rows.find(_._1.vendor == "Loblaws").get._3 == Provenance.HammerFuzzy)
    // and 07.2 actually weighs the fuzzy rows down
    assert(HammerLoader.provenanceFor("NoFrills") == Provenance.HammerFuzzy)
  }

  test("Hammer history is distinguishable from first-party observations") {
    val (_, rows) = loadAll()
    assert(rows.forall(r => r._3 == Provenance.Hammer || r._3 == Provenance.HammerFuzzy))
    assert(!rows.exists(_._3 == Provenance.FirstParty))
  }

  test("a row with an unparseable date is skipped, not fatal") {
    val badRaw        = tempCsv("raw-bad", "product_id,current_price,nowtime\n1,4.99,not-a-date\n")
    val loader        = new CsvHammerLoader[IO](onRow = (_, _, _) => IO.unit)
    val Right(report) = loader.load(productCsv, badRaw).unsafeRunSync()
    assert(report.priceRows == 0 && report.skipped == 1)
  }

  test("CSV splitting honours quoted fields and escaped quotes") {
    assert(HammerLoader.splitCsvLine("""a,"b,c",d""") == List("a", "b,c", "d"))
    assert(HammerLoader.splitCsvLine("a,\"say \"\"hi\"\"\",c") == List("a", "say \"hi\"", "c"))
  }

  test("money parsing tolerates the dollar signs and blanks Hammer emits") {
    assert(HammerLoader.parseMoney("$4.99").contains(Money.cents(499)))
    assert(HammerLoader.parseMoney("4.99").contains(Money.cents(499)))
    assert(HammerLoader.parseMoney("").isEmpty)
  }

  test("a missing file is a typed error, not an exception") {
    val loader = new CsvHammerLoader[IO](onRow = (_, _, _) => IO.unit)
    assert(loader.load(Path.of("/nope/missing.csv"), rawCsv).unsafeRunSync().isLeft)
  }
}
