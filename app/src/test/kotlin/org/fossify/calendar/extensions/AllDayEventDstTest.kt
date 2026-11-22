package org.fossify.calendar.extensions

import org.fossify.calendar.helpers.FLAG_ALL_DAY
import org.fossify.calendar.helpers.Formatter
import org.fossify.calendar.models.Event
import org.joda.time.DateTimeZone
import org.joda.time.LocalDate
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

class AllDayEventDstTest {
    private lateinit var originalZone: DateTimeZone

    @Before
    fun saveZone() {
        originalZone = DateTimeZone.getDefault()
    }

    @After
    fun restoreZone() {
        DateTimeZone.setDefault(originalZone)
    }

    // first and last day of an all-day event, in the zone the device is in
    private data class Case(val zone: String, val first: String, val last: String)

    private val cases = listOf(
        // clocks go back at midnight, so the last day has 25 hours and the extra one is in the evening
        Case("Asia/Beirut", "2026-10-24", "2026-10-24"),
        Case("Asia/Beirut", "2026-10-22", "2026-10-24"),
        Case("America/Santiago", "2027-04-03", "2027-04-03"),
        Case("America/Santiago", "2027-04-01", "2027-04-03"),
        Case("Africa/Cairo", "2026-10-29", "2026-10-29"),
        Case("America/Nuuk", "2026-10-24", "2026-10-24"),
        // clocks go forward at midnight, so the day starts at 01:00
        Case("Asia/Beirut", "2026-03-29", "2026-03-29"),
        Case("Asia/Beirut", "2026-03-27", "2026-03-29"),
        Case("America/Santiago", "2026-09-06", "2026-09-06"),
        Case("America/Havana", "2026-03-08", "2026-03-08"),
        // clocks change at night
        Case("Europe/Berlin", "2026-10-25", "2026-10-25"),
        Case("Europe/Berlin", "2026-10-23", "2026-10-25"),
        Case("Europe/Berlin", "2026-03-29", "2026-03-29"),
        Case("America/Los_Angeles", "2026-11-01", "2026-11-01"),
        Case("America/Los_Angeles", "2026-03-06", "2026-03-08"),
        Case("Australia/Lord_Howe", "2026-04-05", "2026-04-05"),
        // no change
        Case("Europe/Berlin", "2026-07-01", "2026-07-03"),
        Case("UTC", "2026-10-24", "2026-10-24"),
    )

    private fun utcMidnight(date: LocalDate) = date.toDateTimeAtStartOfDay(DateTimeZone.UTC).seconds()

    // what EventActivity stores for an all-day event
    private fun localEvent(case: Case): Event {
        val zone = DateTimeZone.forID(case.zone)
        return Event(
            id = null,
            startTS = LocalDate.parse(case.first).toDateTimeAtStartOfDay(zone).seconds(),
            endTS = LocalDate.parse(case.last).toDateTimeAtStartOfDay(zone).withHourOfDay(12).seconds(),
            timeZone = case.zone,
            flags = FLAG_ALL_DAY,
        )
    }

    // what the calendar provider holds for the same event
    private fun providerEvent(case: Case) = Event(
        id = null,
        startTS = utcMidnight(LocalDate.parse(case.first)),
        endTS = utcMidnight(LocalDate.parse(case.last).plusDays(1)),
        timeZone = DateTimeZone.UTC.id,
        flags = FLAG_ALL_DAY,
    )

    private fun localDate(ts: Long, zone: String) = LocalDate(ts * 1000L, DateTimeZone.forID(zone))

    @Test
    fun toUtcAllDayEventEndsTheDayAfterTheLastDay() {
        for (case in cases) {
            DateTimeZone.setDefault(DateTimeZone.forID(case.zone))
            val event = localEvent(case).apply { toUtcAllDayEvent() }
            val expected = providerEvent(case)
            // the start is only compared by date: on days that begin at 01:00 it is sent as 01:00 UTC, which the
            // calendar provider truncates to midnight for all-day events
            assertEquals("$case start", localDate(expected.startTS, "UTC"), localDate(event.startTS, "UTC"))
            assertEquals("$case end", expected.endTS, event.endTS)
            assertEquals("$case zone", DateTimeZone.UTC.id, event.timeZone)
        }
    }

    @Test
    fun toLocalAllDayEventKeepsTheDates() {
        for (case in cases) {
            DateTimeZone.setDefault(DateTimeZone.forID(case.zone))
            val event = providerEvent(case).apply { toLocalAllDayEvent() }
            assertEquals("$case first day", LocalDate.parse(case.first), localDate(event.startTS, case.zone))
            assertEquals("$case last day", LocalDate.parse(case.last), localDate(event.endTS, case.zone))
        }
    }

    @Test
    fun localToUtcToLocalKeepsTheDates() {
        for (case in cases) {
            DateTimeZone.setDefault(DateTimeZone.forID(case.zone))
            val original = localEvent(case)
            val event = original.copy().apply {
                toUtcAllDayEvent()
                toLocalAllDayEvent()
            }
            assertEquals("$case start", original.startTS, event.startTS)
            assertEquals("$case last day", localDate(original.endTS, case.zone), localDate(event.endTS, case.zone))
        }
    }

    @Test
    fun utcToLocalToUtcIsTheIdentity() {
        for (case in cases) {
            DateTimeZone.setDefault(DateTimeZone.forID(case.zone))
            val original = providerEvent(case)
            val event = original.copy().apply {
                toLocalAllDayEvent()
                toUtcAllDayEvent()
            }
            assertEquals("$case start", localDate(original.startTS, "UTC"), localDate(event.startTS, "UTC"))
            assertEquals("$case end", original.endTS, event.endTS)
        }
    }

    @Test
    fun getShiftedUtcEndTSUsesTheGivenZone() {
        val beirut = DateTimeZone.forID("Asia/Beirut")
        val expected = utcMidnight(LocalDate.parse("2026-10-25"))
        val lastDay = LocalDate.parse("2026-10-24").toDateTimeAtStartOfDay(beirut)
        for (hour in listOf(0, 12, 13, 23)) {
            val ts = lastDay.withHourOfDay(hour).seconds()
            assertEquals("$hour:00", expected, Formatter.getShiftedUtcEndTS(ts, beirut))
        }
        // the last minute of the day, after the clocks went back
        val lastInstant = LocalDate.parse("2026-10-25").toDateTimeAtStartOfDay(beirut).minusMinutes(1)
        assertEquals("23:59", expected, Formatter.getShiftedUtcEndTS(lastInstant.seconds(), beirut))
    }
}
