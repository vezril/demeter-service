package demeter.orchestration

import java.time.Instant

import scala.concurrent.duration._

import cats.effect.IO
import cats.effect.kernel.Ref
import cats.effect.unsafe.implicits.global
import demeter.foundations._
import demeter.ingestion._
import demeter.persistence.RawResponseId
import demeter.alerting.{AlertKey, AlertLedger, AlertRecord}
import demeter.watchlist.{WatchId, WatchItem}
import org.scalatest.funsuite.AnyFunSuite
import InMemory._

/** Spec 08.1 (+ the phase-2 end-to-end gate) — the daily orchestrated run,
  * driven by a scripted FlyerSource and in-memory stores. Tags: @boundary.
  */
final class DailyRunSpec extends AnyFunSuite {

  private val postal = PostalCode.parse("H2X1Y6").toOption.get
  private val from   = Instant.parse("2026-07-23T00:00:00Z")
  private val to     = Instant.parse("2026-08-30T00:00:00Z")

  private val config = Config(
    postalCode = postal,
    locale = Locale.EnCa,
    sinks = SinkConfig(haWebhookUrl = Some("http://ha.local/hook")),
    run = RunConfig(flyerConcurrency = 3),
  )

  // requireSale is explicitly OFF here: these tests exercise the pipeline's
  // mechanics — fan-out, idempotency, dedup, delivery — not deal quality. With
  // it on (the production default) a first run holds no history, every verdict
  // is honestly Unknown, and nothing is delivered, which would test nothing.
  private val milkWatch =
    WatchItem.of(WatchId("w-milk"), "Milk", List("milk", "lait"), requireSale = false).toOption.get

  private def flyer(id: Long, merchant: Int = 100): Flyer =
    Flyer.of(FlyerId(id), MerchantId(merchant), "Weekly", from, to, postal, Locale.EnCa).toOption.get

  private def item(id: String, flyerId: Long, name: String, cents: Option[Long]): FlyerItem =
    FlyerItem(
      sourceItemId = id,
      flyerId = FlyerId(flyerId),
      merchantId = MerchantId(100),
      name = BilingualText.empty,
      rawName = name,
      currentPrice = cents.map(Money.cents(_)),
      originalPrice = None,
      saleStory = None,
      validFrom = from,
      validTo = to,
    )

  private def rawResponse = RawResponse("{}".getBytes, "application/json", from, "https://test/flyers")

  /** A scripted source: records item-fetch calls and tracks peak concurrency. */
  private final class ScriptedSource(
      flyers: List[Flyer],
      itemsOf: FlyerId => Either[DealWatchError, List[FlyerItem]],
      listingResult: Option[Either[DealWatchError, RawFlyerListing]] = None,
      delay: FiniteDuration = Duration.Zero,
      val calls: Ref[IO, List[FlyerId]] = Ref.of[IO, List[FlyerId]](Nil).unsafeRunSync(),
      val inFlight: Ref[IO, Int] = Ref.of[IO, Int](0).unsafeRunSync(),
      val peak: Ref[IO, Int] = Ref.of[IO, Int](0).unsafeRunSync(),
      override val name: SourceName = SourceName("scripted"),
  ) extends FlyerSource[IO] {

    val capabilities: Set[Capability] = Set(Capability.Flyers, Capability.Items, Capability.Search)

    def flyers(p: PostalCode, l: Locale): IO[Either[DealWatchError, RawFlyerListing]] =
      IO.pure(
        listingResult.getOrElse(
          Right(RawFlyerListing(rawResponse, flyers, List(Merchant(MerchantId(100), "Metro")), 0))
        )
      )

    def items(flyerId: FlyerId, p: PostalCode, l: Locale): IO[Either[DealWatchError, RawFlyerItems]] =
      calls.update(_ :+ flyerId) *>
        inFlight.updateAndGet(_ + 1).flatMap(n => peak.update(_ max n)) *>
        IO.sleep(delay) *>
        inFlight.update(_ - 1).as(itemsOf(flyerId).map(items => RawFlyerItems(rawResponse, items, 0)))

    def search(term: String, p: PostalCode, l: Locale): IO[Either[DealWatchError, RawSearchResult]] =
      IO.pure(Left(DealWatchError.Unsupported(name.value, "Search")))
  }

  private def runWith(
      source: FlyerSource[IO],
      sink: MemSink = MemSink.create(),
      obsStore: MemObservationStore = MemObservationStore.create(),
      ledger: MemLedger = MemLedger.create(),
      rawStore: MemRawStore = MemRawStore.create(),
      fallback: Option[FlyerSource[IO]] = None,
      cfg: Config = config,
      watchlist: List[WatchItem] = List(milkWatch),
      alertLedger: MemAlertLedger = MemAlertLedger.create(),
  ): (RunReport, MemSink, MemObservationStore, MemRawStore, MemLedger) = {
    val run = DailyRun
      .create[IO](source, fallback, rawStore, obsStore, ledger, sink, alertLedger, cfg, watchlist)
      .unsafeRunSync()
    (run.run.unsafeRunSync(), sink, obsStore, rawStore, ledger)
  }

  test("a normal run fetches only ledger-selected flyers") {
    val flyers = (1 to 100).map(i => flyer(i.toLong)).toList
    val ledger = MemLedger.create()
    // pretend 88 were already fetched for this exact window
    flyers.take(88).foreach(f => ledger.markFetched(f.id, (f.validFrom, f.validTo), RawResponseId(1)).unsafeRunSync())

    val source               = new ScriptedSource(flyers, _ => Right(Nil))
    val (report, _, _, _, _) = runWith(source, ledger = ledger)

    assert(report.flyersListed == 100)
    assert(report.flyersSelected == 12)
    assert(source.calls.get.unsafeRunSync().size == 12)
  }

  test("one failing flyer does not sink the run") {
    val flyers = (1 to 12).map(i => flyer(i.toLong)).toList
    val source = new ScriptedSource(
      flyers,
      id =>
        if (id.value == 7L) Left(DealWatchError.Decode("scripted", "items[0]", "boom"))
        else Right(List(item(s"i${id.value}", id.value, "Natrel Milk 4 L", Some(499L)))),
    )
    val (report, _, obs, _, _) = runWith(source)

    assert(report.flyersFetched == 11)
    assert(report.flyersFailed == 1)
    assert(obs.saved.get.unsafeRunSync().size == 11)
    assert(report.failures.exists(_.isInstanceOf[DealWatchError.Decode]))
  }

  test("bounded concurrency is respected during fan-out") {
    val flyers = (1 to 12).map(i => flyer(i.toLong)).toList
    val source = new ScriptedSource(flyers, _ => Right(Nil), delay = 20.millis)
    runWith(source, cfg = config.copy(run = RunConfig(flyerConcurrency = 3)))
    assert(source.peak.get.unsafeRunSync() <= 3)
  }

  test("the raw response is archived before observations are stored") {
    val source = new ScriptedSource(List(flyer(1)), _ => Right(List(item("i1", 1L, "Natrel Milk 4 L", Some(499L)))))
    val (_, _, obs, raw, _) = runWith(source)
    assert(raw.rows.get.unsafeRunSync().size == 1)
    assert(obs.saved.get.unsafeRunSync().size == 1)
  }

  test("a run that delivers records how many consumers were listening") {
    val flyers = List(flyer(1))
    val items  = List(item("i1", 1L, "Natrel Milk 4 L", Some(499L)))
    val (report, sink, _, _, _) =
      runWith(new ScriptedSource(flyers, _ => Right(items)), sink = MemSink.create(subscribers = Some(0)))

    assume(sink.delivered.get.unsafeRunSync().nonEmpty, "this case needs a delivery to be meaningful")
    assert(report.alertsDelivered > 0)
    assert(report.alertAudience.contains(0), "the empty channel must reach the report, or 08.3 cannot see it")
    assert(Observability.alarms(report, SourceName("flipp")).exists(_.isInstanceOf[DriftAlarm.NoAudience]))
  }

  test("a run that delivers nothing does not ask who was listening") {
    // Nothing was sent, so an empty channel is not a problem to report. The
    // sink offers a count here and the run must still leave it unknown.
    val (report, sink, _, _, _) =
      runWith(new ScriptedSource(Nil, _ => Right(Nil)), sink = MemSink.create(subscribers = Some(0)))

    assert(sink.delivered.get.unsafeRunSync().isEmpty)
    assert(report.alertAudience.isEmpty, "an unasked question has no answer")
    assert(!Observability.alarms(report, SourceName("flipp")).exists(_.isInstanceOf[DriftAlarm.NoAudience]))
  }

  test("the run is idempotent within a day: no duplicate observations, no duplicate alerts") {
    val flyers   = List(flyer(1))
    val items    = List(item("i1", 1L, "Natrel Milk 4 L", Some(499L)))
    val rawStore = MemRawStore.create()
    val obsStore = MemObservationStore.create()
    val ledger   = MemLedger.create()
    val sink     = MemSink.create()

    val source = new ScriptedSource(flyers, _ => Right(items))
    val run = DailyRun
      .create[IO](source, None, rawStore, obsStore, ledger, sink, MemAlertLedger.create(), config, List(milkWatch))
      .unsafeRunSync()

    val first  = run.run.unsafeRunSync()
    val second = run.run.unsafeRunSync()

    assert(obsStore.saved.get.unsafeRunSync().size == 1, "the second run must not duplicate observations")
    assert(sink.delivered.get.unsafeRunSync().size <= 1, "the second run must not duplicate alerts")
    assert(second.flyersSelected == 0, "the ledger must skip the already-fetched flyer")
    assert(first.flyersFetched == 1)
  }

  test("a matched watched item at a good price is delivered exactly once") {
    val source = new ScriptedSource(List(flyer(1)), _ => Right(List(item("i1", 1L, "Natrel Milk 4 L", Some(499L)))))
    val (report, sink, _, _, _) = runWith(source)
    assert(report.matches == 1)
    assert(sink.delivered.get.unsafeRunSync().size == 1)
    val alert = sink.delivered.get.unsafeRunSync().head
    assert(alert.watchLabel == "Milk")
    assert(alert.merchantName == "Metro") // resolved from the listing's merchant join
  }

  test("an unmatched item produces no alert") {
    val source =
      new ScriptedSource(List(flyer(1)), _ => Right(List(item("i1", 1L, "MASTERCRAFT Socket Set", Some(2999L)))))
    val (report, sink, _, _, _) = runWith(source)
    assert(report.matches == 0)
    assert(sink.delivered.get.unsafeRunSync().isEmpty)
  }

  test("a listing bot wall switches to the fallback source") {
    val botWall = DealWatchError.BotWall("https://backflipp", "cf-chl-bypass")
    val blocked = new ScriptedSource(Nil, _ => Right(Nil), listingResult = Some(Left(botWall)))
    val fallback = new ScriptedSource(
      List(flyer(1)),
      _ => Right(List(item("i1", 1L, "Natrel Milk 4 L", Some(499L)))),
      name = SourceName("apify"),
    )

    val (report, _, _, _, _) = runWith(blocked, fallback = Some(fallback))
    assert(report.degraded.exists(_.reason.isInstanceOf[DealWatchError.BotWall]))
    assert(report.partial)
    assert(report.flyersListed == 1, "the fallback listing must be used")
  }

  test("a listing bot wall with no fallback yields a clean partial run, not a crash") {
    val botWall                 = DealWatchError.BotWall("https://backflipp", "cf-chl")
    val blocked                 = new ScriptedSource(Nil, _ => Right(Nil), listingResult = Some(Left(botWall)))
    val (report, sink, _, _, _) = runWith(blocked)
    assert(report.partial)
    assert(report.flyersFetched == 0)
    assert(sink.delivered.get.unsafeRunSync().isEmpty)
    assert(report.degraded.size == 1)
  }

  test("a store failure is recorded per flyer rather than thrown") {
    val source = new ScriptedSource(List(flyer(1)), _ => Right(List(item("i1", 1L, "Natrel Milk 4 L", Some(499L)))))
    val broken = MemObservationStore.create(failWith = Some(DealWatchError.StoreUnavailable("down")))
    val (report, _, _, _, _) = runWith(source, obsStore = broken)
    assert(report.flyersFailed == 1)
    assert(report.failures.exists(_.isInstanceOf[DealWatchError.StoreUnavailable]))
  }

  test("a sink failure is recorded without losing the run") {
    val source = new ScriptedSource(List(flyer(1)), _ => Right(List(item("i1", 1L, "Natrel Milk 4 L", Some(499L)))))
    val (report, _, _, _, _) = runWith(source, sink = MemSink.create(fail = true))
    assert(report.flyersFetched == 1)
    assert(report.alertsDelivered == 0)
    assert(report.failures.nonEmpty)
  }

  test("the report records elapsed time and item counts") {
    val source = new ScriptedSource(List(flyer(1)), _ => Right(List(item("i1", 1L, "Natrel Milk 4 L", Some(499L)))))
    val (report, _, _, _, _) = runWith(source)
    assert(report.elapsed.isDefined)
    assert(report.itemsParsed == 1)
    assert(report.observationsInserted == 1)
  }

  test("dedup survives a restart: a fresh run does not re-alert what a previous process already sent") {
    val flyers    = List(flyer(1))
    val items     = List(item("i1", 1L, "Natrel Milk 4 L", Some(499L)))
    val obsStore  = MemObservationStore.create()
    val memLedger = MemLedger.create()
    // the one component that outlives the process, exactly like the real table
    val alertLedger = MemAlertLedger.create()

    def freshProcess(sink: MemSink) = {
      val source = new ScriptedSource(flyers, _ => Right(items))
      DailyRun
        .create[IO](source, None, MemRawStore.create(), obsStore, memLedger, sink, alertLedger, config, List(milkWatch))
        .unsafeRunSync()
    }

    val firstSink = MemSink.create()
    freshProcess(firstSink).run.unsafeRunSync()
    assert(firstSink.delivered.get.unsafeRunSync().size == 1, "first process alerts once")
    assert(alertLedger.entries.get.unsafeRunSync().size == 1, "and records it durably")

    // simulate a restart: brand-new DailyRun, brand-new in-memory Ref, same ledger
    val secondSink = MemSink.create()
    freshProcess(secondSink).run.unsafeRunSync()
    assert(secondSink.delivered.get.unsafeRunSync().isEmpty, "a restart must not re-alert the same deal")
  }

  test("a price drop still re-alerts after a restart, and the ledger records the lower price") {
    val obsStore    = MemObservationStore.create()
    val alertLedger = MemAlertLedger.create()

    def processWith(cents: Long, sink: MemSink) = {
      val source = new ScriptedSource(List(flyer(1)), _ => Right(List(item("i1", 1L, "Natrel Milk 4 L", Some(cents)))))
      DailyRun
        .create[IO](
          source,
          None,
          MemRawStore.create(),
          obsStore,
          MemLedger.create(),
          sink,
          alertLedger,
          config,
          List(milkWatch),
        )
        .unsafeRunSync()
    }

    val first = MemSink.create()
    processWith(499L, first).run.unsafeRunSync()
    assert(first.delivered.get.unsafeRunSync().size == 1)

    // restart, and the price has dropped inside the same flyer window
    val second = MemSink.create()
    processWith(399L, second).run.unsafeRunSync()
    assert(second.delivered.get.unsafeRunSync().size == 1, "a better deal is still news after a restart")
    assert(
      alertLedger.entries.get.unsafeRunSync().values.head.alertedPrice.map(_.cents).contains(399L),
      "the ledger must carry the NEW lower price, or a later smaller drop would be judged against a stale figure",
    )
  }

  test("a ledger write failure is recorded but does not lose the run") {
    val failing = new AlertLedger[IO] {
      def openAt(now: Instant): IO[Map[AlertKey, AlertRecord]] = IO.pure(Map.empty)
      def record(entry: AlertRecord): IO[Either[DealWatchError, Unit]] =
        IO.pure(Left(DealWatchError.StoreUnavailable("ledger down")))
      def prune(cutoff: Instant): IO[Int] = IO.pure(0)
    }
    val source = new ScriptedSource(List(flyer(1)), _ => Right(List(item("i1", 1L, "Natrel Milk 4 L", Some(499L)))))
    val sink   = MemSink.create()
    val run = DailyRun
      .create[IO](
        source,
        None,
        MemRawStore.create(),
        MemObservationStore.create(),
        MemLedger.create(),
        sink,
        failing,
        config,
        List(milkWatch),
      )
      .unsafeRunSync()

    val report = run.run.unsafeRunSync()
    assert(sink.delivered.get.unsafeRunSync().size == 1, "the alert genuinely went out")
    assert(report.alertsDelivered == 1)
    assert(
      report.failures.exists(_.isInstanceOf[DealWatchError.StoreUnavailable]),
      "and the bookkeeping failure is visible",
    )
  }
}
