package org.fossify.calendar.helpers

import org.fossify.calendar.extensions.seconds
import org.joda.time.DateTime
import org.joda.time.DateTimeZone
import org.joda.time.LocalDate

/**
 * The stored instants of an all-day event: [startTS] is the first instant of the first day and [endTS] lies on the last
 * day, both in the time zone the event was stored in. [repeatLimit] is a timestamp only when positive.
 */
data class AllDayTimes(val startTS: Long, val endTS: Long, val repeatLimit: Long)

/**
 * Moves an all-day event stored in [from] so that it covers the same dates in [to]. Synced events get exactly what a
 * CalDAV sync derives from the provider row in [to] (see toLocalAllDayEvent), so the next sync finds nothing to change.
 */
fun reanchorAllDayTimes(times: AllDayTimes, from: DateTimeZone, to: DateTimeZone, isSynced: Boolean): AllDayTimes {
    val startDate = LocalDate(times.startTS * 1000L, from)
    val endDate = LocalDate(times.endTS * 1000L, from)
    return if (isSynced) {
        val startTS = getSyncedStartOfDay(startDate, to)
        val endTS = if (times.endTS > times.startTS) {
            getSyncedStartOfDay(endDate.plusDays(1), to) - TWELVE_HOURS
        } else {
            startTS
        }

        // an UNTIL date is stored as the start of that day, an UNTIL date-time is an absolute instant
        val repeatLimit = if (times.repeatLimit > 0 && isStartOfDay(times.repeatLimit, from)) {
            getSyncedStartOfDay(LocalDate(times.repeatLimit * 1000L, from), to)
        } else {
            times.repeatLimit
        }
        AllDayTimes(startTS, endTS, repeatLimit)
    } else {
        AllDayTimes(
            startTS = startDate.toDateTimeAtStartOfDay(to).seconds(),
            endTS = keepLocalDateTime(times.endTS, from, to),
            repeatLimit = if (times.repeatLimit > 0) {
                keepLocalDateTime(times.repeatLimit, from, to)
            } else {
                times.repeatLimit
            }
        )
    }
}

// the same as Formatter.getShiftedLocalTS on the UTC midnight a calendar provider stores for an all-day date
private fun getSyncedStartOfDay(date: LocalDate, zone: DateTimeZone) =
    Formatter.getShiftedTS(dateTime = date.toDateTimeAtStartOfDay(DateTimeZone.UTC), toZone = zone)

private fun isStartOfDay(ts: Long, zone: DateTimeZone): Boolean {
    val dateTime = DateTime(ts * 1000L, zone)
    return dateTime == dateTime.withTimeAtStartOfDay()
}

// same date and wall-clock time; the start of a day stays the start of that day even where midnight does not exist
private fun keepLocalDateTime(ts: Long, from: DateTimeZone, to: DateTimeZone): Long {
    val dateTime = DateTime(ts * 1000L, from)
    return if (isStartOfDay(ts, from)) {
        dateTime.toLocalDate().toDateTimeAtStartOfDay(to).seconds()
    } else {
        dateTime.withZoneRetainFields(to).seconds()
    }
}
