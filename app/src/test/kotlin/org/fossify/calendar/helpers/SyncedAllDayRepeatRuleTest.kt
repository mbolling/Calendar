package org.fossify.calendar.helpers

import org.fossify.calendar.extensions.seconds
import org.fossify.calendar.extensions.toLocalAllDayEvent
import org.fossify.calendar.models.Event
import org.fossify.commons.helpers.SUNDAY_BIT
import org.fossify.commons.helpers.WEDNESDAY_BIT
import org.joda.time.DateTimeZone
import org.joda.time.LocalDate
import org.joda.time.LocalTime
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

/**
 * The calendar provider stores the start of an all-day event as UTC midnight of its date, so rules that take the
 * weekday from the start (FREQ=WEEKLY without BYDAY, FREQ=DAILY;INTERVAL=7n) must read it on the local start day.
 */
class SyncedAllDayRepeatRuleTest {
    private val zones = listOf(
        "America/New_York",
        "America/Los_Angeles",
        "Pacific/Honolulu",
        "UTC",
        "Europe/Berlin",
        "Pacific/Auckland",
        "Pacific/Kiritimati"
    ).map { DateTimeZone.forID(it) }

    // Wednesday
    private val date = LocalDate(2026, 12, 2)

    private lateinit var originalDefault: DateTimeZone

    @Before
    fun saveDefault() {
        originalDefault = DateTimeZone.getDefault()
    }

    @After
    fun restoreDefault() {
        DateTimeZone.setDefault(originalDefault)
    }

    private fun providerStartTS(day: LocalDate) = day.toDateTimeAtStartOfDay(DateTimeZone.UTC).seconds()

    private fun parseAllDay(rrule: String, day: LocalDate = date) =
        Parser().parseProviderRepeatInterval(rrule, providerStartTS(day), isAllDay = true)

    @Test
    fun weeklyWithoutByDayRepeatsOnTheStartWeekday() {
        zones.forEach { zone ->
            DateTimeZone.setDefault(zone)
            val repetition = parseAllDay("FREQ=WEEKLY")
            assertEquals(zone.id, WEEK, repetition.repeatInterval)
            assertEquals(zone.id, WEDNESDAY_BIT, repetition.repeatRule)
        }
    }

    @Test
    fun everyTwoWeeksAsDailyRepeatsOnTheStartWeekday() {
        zones.forEach { zone ->
            DateTimeZone.setDefault(zone)
            val repetition = parseAllDay("FREQ=DAILY;INTERVAL=14")
            assertEquals(zone.id, 2 * WEEK, repetition.repeatInterval)
            assertEquals(zone.id, WEDNESDAY_BIT, repetition.repeatRule)
        }
    }

    @Test
    fun repeatRuleMatchesTheStoredStart() {
        zones.forEach { zone ->
            DateTimeZone.setDefault(zone)
            val event = Event(
                id = null,
                startTS = providerStartTS(date),
                endTS = providerStartTS(date.plusDays(1)),
                flags = FLAG_ALL_DAY
            )
            event.toLocalAllDayEvent()

            val storedStart = Formatter.getDateTimeFromTS(event.startTS)
            assertEquals(zone.id, date, storedStart.toLocalDate())
            assertEquals(zone.id, 1 shl (storedStart.dayOfWeek - 1), parseAllDay("FREQ=WEEKLY").repeatRule)
        }
    }

    @Test
    fun weeklyStartingOnADaylightSavingChangeDay() {
        // all Sundays; Santiago skips midnight when its daylight saving time starts (00:00 -> 01:00)
        listOf(
            "America/New_York" to LocalDate(2026, 3, 8),
            "America/New_York" to LocalDate(2026, 11, 1),
            "America/Santiago" to LocalDate(2026, 9, 6),
            "America/Santiago" to LocalDate(2026, 4, 5),
            "Pacific/Auckland" to LocalDate(2026, 4, 5),
            "Pacific/Auckland" to LocalDate(2026, 9, 27)
        ).forEach { (zoneId, day) ->
            DateTimeZone.setDefault(DateTimeZone.forID(zoneId))
            assertEquals("$zoneId $day", SUNDAY_BIT, parseAllDay("FREQ=WEEKLY", day).repeatRule)
        }
    }

    @Test
    fun timedEventsKeepTheirStart() {
        // Wednesday 21:00 in New York is Thursday 02:00 UTC
        val startTS = date.toDateTime(LocalTime(21, 0), DateTimeZone.forID("America/New_York")).seconds()
        zones.forEach { zone ->
            DateTimeZone.setDefault(zone)
            assertEquals(
                zone.id,
                Parser().parseRepeatInterval("FREQ=WEEKLY", startTS).repeatRule,
                Parser().parseProviderRepeatInterval("FREQ=WEEKLY", startTS, isAllDay = false).repeatRule
            )
        }
    }
}
