package demeter.orchestration

import java.time.{DayOfWeek, Instant, ZoneId, ZonedDateTime}

import scala.util.Try

/** Spec 08.4 — when the daily run fires.
  *
  * `ScheduleConfig.cron` was previously parsed into config and then ignored; the
  * loop just slept 24h. That drifts (a five-minute run pushes tomorrow five
  * minutes later, compounding daily), fires relative to process start rather
  * than to a wall-clock time, and resets on every restart.
  *
  * This supports the subset a flyer job actually needs — a time of day, on all
  * days or on named weekdays — and REFUSES anything else at boot rather than
  * silently approximating it. Supporting a fraction of cron while accepting the
  * whole syntax would be worse than not accepting cron at all.
  *
  * Fields: `minute hour day-of-month month day-of-week`. Day-of-month and month
  * must be `*`; day-of-week accepts `*`, single values, lists (`1,4`), and
  * ranges (`1-5`), numbered 0/7=Sunday through 6=Saturday.
  */
final case class DailySchedule(
    minute: Int,
    hour: Int,
    daysOfWeek: Set[DayOfWeek],
    zone: ZoneId,
) {

  /** The next firing strictly after `now`.
    *
    * Recomputed from the clock on every iteration rather than accumulated, so a
    * slow run cannot drag the schedule later and a restart lands back on the
    * same wall-clock slot.
    */
  def nextAfter(now: Instant): Instant = {
    val local = now.atZone(zone)
    // at most 8 candidates: today plus a full week covers any weekday set
    LazyList
      .from(0)
      .take(8)
      .map(d => candidateOn(local.plusDays(d.toLong)))
      .find(c => c.toInstant.isAfter(now) && daysOfWeek.contains(c.getDayOfWeek))
      .map(_.toInstant)
      .getOrElse(throw new IllegalStateException(s"no firing found within 8 days for $this"))
  }

  /** ZonedDateTime resolves DST for us: a time that does not exist on a
    * spring-forward day shifts forward, and an ambiguous fall-back time takes
    * the earlier offset. Neither case may throw — the job just runs once.
    */
  private def candidateOn(day: ZonedDateTime): ZonedDateTime =
    day.toLocalDate.atTime(hour, minute).atZone(zone)

  def describe: String = {
    val days =
      if (daysOfWeek == DailySchedule.EveryDay) "every day"
      else daysOfWeek.toList.sorted.map(_.toString.take(3).toLowerCase).mkString(",")
    f"$hour%02d:$minute%02d $zone ($days)"
  }
}

object DailySchedule {

  val EveryDay: Set[DayOfWeek] = DayOfWeek.values().toSet

  def parse(cron: String, zone: ZoneId): Either[String, DailySchedule] = {
    val fields = cron.trim.split("\\s+").toList
    fields match {
      case min :: hr :: dom :: mon :: dow :: Nil =>
        for {
          m <- number(min, 0, 59, "minute")
          h <- number(hr, 0, 23, "hour")
          _ <- Either.cond(
            dom == "*",
            (),
            s"day-of-month must be '*' (got '$dom'): only a daily or weekly schedule is supported",
          )
          _ <- Either.cond(
            mon == "*",
            (),
            s"month must be '*' (got '$mon'): only a daily or weekly schedule is supported",
          )
          d <- daysOf(dow)
        } yield DailySchedule(m, h, d, zone)
      case _ =>
        Left(s"expected 5 cron fields 'minute hour day-of-month month day-of-week', got ${fields.size} in '$cron'")
    }
  }

  private def number(field: String, lo: Int, hi: Int, label: String): Either[String, Int] =
    Try(field.toInt).toOption
      .filter(n => n >= lo && n <= hi)
      .toRight(s"$label must be a number in $lo..$hi (got '$field'); ranges and steps are not supported here")

  private def daysOf(field: String): Either[String, Set[DayOfWeek]] =
    if (field == "*") Right(EveryDay)
    else {
      val parsed = field.split(",").toList.map {
        case r if r.contains("-") =>
          r.split("-", 2).toList match {
            case a :: b :: Nil =>
              (dayOf(a), dayOf(b)) match {
                case (Right(x), Right(y)) => Right(rangeBetween(x, y))
                case (Left(e), _)         => Left(e)
                case (_, Left(e))         => Left(e)
              }
            case _ => Left(s"malformed day-of-week range '$r'")
          }
        case single => dayOf(single).map(Set(_))
      }
      parsed.collectFirst { case Left(e) => e } match {
        case Some(err) => Left(err)
        case None      => Right(parsed.collect { case Right(s) => s }.reduce(_ ++ _))
      }
    }

  /** cron numbers Sunday as both 0 and 7. */
  private def dayOf(s: String): Either[String, DayOfWeek] =
    Try(s.trim.toInt).toOption
      .flatMap {
        case 0 | 7                 => Some(DayOfWeek.SUNDAY)
        case n if n >= 1 && n <= 6 => Some(DayOfWeek.of(n))
        case _                     => None
      }
      .toRight(s"day-of-week must be 0..7 with 0 or 7 meaning Sunday (got '$s')")

  private def rangeBetween(from: DayOfWeek, to: DayOfWeek): Set[DayOfWeek] = {
    val all = (0 until 7).map(i => from.plus(i.toLong)).toList
    all.take(all.indexOf(to) + 1).toSet
  }
}
