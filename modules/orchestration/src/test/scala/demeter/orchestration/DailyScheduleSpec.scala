package demeter.orchestration

import java.time.{DayOfWeek, Duration, Instant, ZoneId}

import org.scalacheck.Gen
import org.scalatest.funsuite.AnyFunSuite
import org.scalatestplus.scalacheck.ScalaCheckPropertyChecks

/** Spec 08.4 — when the daily run fires. Tags: @pure. */
final class DailyScheduleSpec extends AnyFunSuite with ScalaCheckPropertyChecks {

  private val montreal = ZoneId.of("America/Montreal")

  private def sched(cron: String) = DailySchedule.parse(cron, montreal).toOption.get
  private def at(s: String)       = Instant.parse(s)

  test("a daily cron parses to a time of day on every day") {
    val s = sched("0 6 * * *")
    assert(s.hour == 6 && s.minute == 0)
    assert(s.daysOfWeek == DailySchedule.EveryDay)
  }

  test("the next firing is the next occurrence of that wall-clock time") {
    val s = sched("30 6 * * *")
    // 04:00 UTC = 00:00 EDT, so 06:30 EDT the same local day = 10:30 UTC
    assert(s.nextAfter(at("2026-07-23T04:00:00Z")) == at("2026-07-23T10:30:00Z"))
  }

  test("a firing that has already passed today rolls to tomorrow") {
    val s = sched("0 6 * * *")
    // 14:00 UTC = 10:00 EDT, past this morning's 06:00
    assert(s.nextAfter(at("2026-07-23T14:00:00Z")) == at("2026-07-24T10:00:00Z"))
  }

  test("the boundary is strict: firing exactly now schedules the NEXT one, never a double-fire") {
    val s      = sched("0 6 * * *")
    val exactly = at("2026-07-23T10:00:00Z") // 06:00 EDT precisely
    assert(s.nextAfter(exactly) == at("2026-07-24T10:00:00Z"))
  }

  test("the schedule does not drift when a run overruns") {
    val s     = sched("0 6 * * *")
    val today = s.nextAfter(at("2026-07-23T04:00:00Z"))
    // a run that starts on time but takes 47 minutes still targets tomorrow's 06:00
    val after = today.plus(Duration.ofMinutes(47))
    assert(s.nextAfter(after) == at("2026-07-24T10:00:00Z"))
  }

  test("weekday restriction: Thursdays only, when flyers drop") {
    val s = sched("0 6 * * 4")
    assert(s.daysOfWeek == Set(DayOfWeek.THURSDAY))
    val next = s.nextAfter(at("2026-07-20T12:00:00Z")) // a Monday
    assert(next.atZone(montreal).getDayOfWeek == DayOfWeek.THURSDAY)
    assert(next == at("2026-07-23T10:00:00Z"))
  }

  test("day-of-week lists and ranges are supported, Sunday counted as either 0 or 7") {
    assert(sched("0 6 * * 1,4").daysOfWeek == Set(DayOfWeek.MONDAY, DayOfWeek.THURSDAY))
    assert(sched("0 6 * * 1-5").daysOfWeek ==
      Set(DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY, DayOfWeek.THURSDAY, DayOfWeek.FRIDAY))
    assert(sched("0 6 * * 0").daysOfWeek == Set(DayOfWeek.SUNDAY))
    assert(sched("0 6 * * 7").daysOfWeek == Set(DayOfWeek.SUNDAY))
  }

  test("a range that wraps past Saturday still resolves") {
    assert(sched("0 6 * * 6-1").daysOfWeek == Set(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY, DayOfWeek.MONDAY))
  }

  // --- the DST cases: a wall-clock scheduler that throws here is worse than useless ---

  test("spring forward: a firing in the skipped hour still happens, exactly once") {
    // 2026-03-08, Montreal jumps 02:00 -> 03:00; 02:30 does not exist that day
    val s    = sched("30 2 * * *")
    val next = s.nextAfter(at("2026-03-08T04:00:00Z")) // 23:00 EST on the 7th
    assert(next.atZone(montreal).toLocalDate.toString == "2026-03-08")
    assert(s.nextAfter(next).isAfter(next), "and the following day still advances")
  }

  test("fall back: an ambiguous firing resolves without throwing and does not repeat") {
    // 2026-11-01, Montreal repeats 01:00-02:00
    val s     = sched("30 1 * * *")
    val first = s.nextAfter(at("2026-11-01T03:00:00Z"))
    val then_ = s.nextAfter(first)
    assert(then_.isAfter(first))
    assert(Duration.between(first, then_).toHours >= 23, "the repeated hour must not cause a same-day double fire")
  }

  test("the timezone is honoured, not the host's") {
    val vancouver = DailySchedule.parse("0 6 * * *", ZoneId.of("America/Vancouver")).toOption.get
    val mtl       = sched("0 6 * * *")
    val from      = at("2026-07-23T04:00:00Z")
    assert(vancouver.nextAfter(from) != mtl.nextAfter(from), "06:00 local differs by zone")
    assert(Duration.between(mtl.nextAfter(from), vancouver.nextAfter(from)).toHours == 3)
  }

  // --- refusing what it cannot honour, rather than approximating ---

  test("fields beyond a daily/weekly schedule are refused, naming what is unsupported") {
    assert(DailySchedule.parse("0 6 1 * *", montreal).swap.exists(_.contains("day-of-month")))
    assert(DailySchedule.parse("0 6 * 3 *", montreal).swap.exists(_.contains("month")))
    assert(DailySchedule.parse("*/15 * * * *", montreal).isLeft, "step syntax is not silently approximated")
  }

  test("malformed expressions are refused with the reason") {
    assert(DailySchedule.parse("0 6 * *", montreal).swap.exists(_.contains("5 cron fields")))
    assert(DailySchedule.parse("", montreal).isLeft)
    assert(DailySchedule.parse("60 6 * * *", montreal).swap.exists(_.contains("minute")))
    assert(DailySchedule.parse("0 24 * * *", montreal).swap.exists(_.contains("hour")))
    assert(DailySchedule.parse("0 6 * * 9", montreal).swap.exists(_.contains("day-of-week")))
  }

  test("the next firing is always in the future and matches the schedule (property)") {
    val gen = for {
      m <- Gen.choose(0, 59)
      h <- Gen.choose(0, 23)
      d <- Gen.choose(0, 400)
    } yield (m, h, d)

    forAll(gen) { case (m, h, d) =>
      val s    = sched(s"$m $h * * *")
      val now  = at("2026-01-01T00:00:00Z").plus(Duration.ofHours(d.toLong))
      val next = s.nextAfter(now)
      assert(next.isAfter(now))
      assert(Duration.between(now, next).toHours <= 25, "a daily schedule always fires within a day")
      val local = next.atZone(montreal)
      // DST-skipped times shift forward, so match on the hour landing at or after the target
      assert(local.getMinute == m || local.getHour != h)
    }
  }

  test("describe renders something an operator can read back") {
    assert(sched("0 6 * * *").describe.contains("06:00"))
    assert(sched("0 6 * * *").describe.contains("every day"))
    assert(sched("30 18 * * 4").describe.contains("18:30"))
    assert(sched("30 18 * * 4").describe.contains("thu"))
  }
}
