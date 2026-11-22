package org.fossify.calendar.helpers

import org.fossify.calendar.extensions.seconds
import org.fossify.calendar.extensions.toLocalAllDayEvent
import org.fossify.calendar.models.Event
import org.joda.time.DateTime
import org.joda.time.DateTimeZone
import org.joda.time.LocalDate
import org.joda.time.LocalTime
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

class AllDayTimesTest {
    private val berlin = DateTimeZone.forID("Europe/Berlin")
    private val losAngeles = DateTimeZone.forID("America/Los_Angeles")
    private val auckland = DateTimeZone.forID("Pacific/Auckland")
    private val santiago = DateTimeZone.forID("America/Santiago")

    private lateinit var originalDefault: DateTimeZone

    @Before
    fun saveDefault() {
        originalDefault = DateTimeZone.getDefault()
    }

    @After
    fun restoreDefault() {
        DateTimeZone.setDefault(originalDefault)
    }

    // what EventActivity stores: midnight of the first day, noon of the last day
    private fun local(first: LocalDate, last: LocalDate, zone: DateTimeZone, repeatLimit: Long = 0) = AllDayTimes(
        startTS = first.toDateTimeAtStartOfDay(zone).seconds(),
        endTS = last.toDateTimeAtStartOfDay(zone).withHourOfDay(12).seconds(),
        repeatLimit = repeatLimit
    )

    // what a CalDAV sync stores for a provider row with the UTC dates first..last (DTEND exclusive)
    private fun synced(first: LocalDate, last: LocalDate, zone: DateTimeZone, until: LocalDate? = null): AllDayTimes {
        DateTimeZone.setDefault(zone)
        val event = Event(
            id = null,
            startTS = first.toDateTimeAtStartOfDay(DateTimeZone.UTC).seconds(),
            endTS = last.plusDays(1).toDateTimeAtStartOfDay(DateTimeZone.UTC).seconds(),
            flags = FLAG_ALL_DAY
        )
        event.toLocalAllDayEvent()
        val repeatLimit = until?.let {
            Formatter.getShiftedTS(it.toDateTimeAtStartOfDay(DateTimeZone.UTC), zone)
        } ?: 0L
        return AllDayTimes(event.startTS, event.endTS, repeatLimit)
    }

    private fun dates(times: AllDayTimes, zone: DateTimeZone) = Pair(
        LocalDate(times.startTS * 1000L, zone),
        LocalDate(times.endTS * 1000L, zone)
    )

    @Test
    fun movingWestKeepsTheDate() {
        val day = LocalDate(2026, 11, 22)
        val result = reanchorAllDayTimes(local(day, day, berlin), berlin, losAngeles, isSynced = false)
        assertEquals(local(day, day, losAngeles), result)
        assertEquals(Pair(day, day), dates(result, losAngeles))
    }

    @Test
    fun movingEastKeepsTheDate() {
        val day = LocalDate(2026, 11, 22)
        val result = reanchorAllDayTimes(local(day, day, berlin), berlin, auckland, isSynced = false)
        assertEquals(local(day, day, auckland), result)
    }

    @Test
    fun multiDayEventKeepsAllItsDates() {
        val first = LocalDate(2026, 11, 24)
        val last = LocalDate(2026, 11, 26)
        val result = reanchorAllDayTimes(local(first, last, berlin), berlin, losAngeles, isSynced = false)
        assertEquals(local(first, last, losAngeles), result)
        assertEquals(Pair(first, last), dates(result, losAngeles))
    }

    @Test
    fun zonesMoreThanTwelveHoursApart() {
        val first = LocalDate(2026, 11, 24)
        val last = LocalDate(2026, 11, 26)
        assertEquals(
            local(first, last, losAngeles),
            reanchorAllDayTimes(local(first, last, auckland), auckland, losAngeles, isSynced = false)
        )
        assertEquals(
            local(first, last, auckland),
            reanchorAllDayTimes(local(first, last, losAngeles), losAngeles, auckland, isSynced = false)
        )
    }

    @Test
    fun daysWithDaylightSavingTransitions() {
        // Los Angeles springs forward on 2026-03-08 and falls back on 2026-11-01, Berlin on 2026-03-29 and 2026-10-25
        for (day in listOf(LocalDate(2026, 3, 8), LocalDate(2026, 11, 1))) {
            assertEquals(
                local(day, day, losAngeles),
                reanchorAllDayTimes(local(day, day, berlin), berlin, losAngeles, isSynced = false)
            )
        }
        for (day in listOf(LocalDate(2026, 3, 29), LocalDate(2026, 10, 25))) {
            assertEquals(
                local(day, day, berlin),
                reanchorAllDayTimes(local(day, day, losAngeles), losAngeles, berlin, isSynced = false)
            )
        }
    }

    @Test
    fun dayWithoutMidnightStartsAtItsFirstInstant() {
        // Santiago skips from 2026-09-06 00:00 to 01:00
        val day = LocalDate(2026, 9, 6)
        val firstInstant = day.toDateTimeAtStartOfDay(santiago)
        assertEquals(LocalTime(1, 0), firstInstant.toLocalTime())

        val result = reanchorAllDayTimes(local(day, day, berlin), berlin, santiago, isSynced = false)
        assertEquals(firstInstant.seconds(), result.startTS)
        assertEquals(Pair(day, day), dates(result, santiago))

        val back = reanchorAllDayTimes(result, santiago, berlin, isSynced = false)
        assertEquals(local(day, day, berlin), back)
    }

    @Test
    fun repeatLimitKeepsItsDateAndTime() {
        val day = LocalDate(2026, 11, 18)
        val limit = DateTime(2026, 12, 9, 23, 59, 59, berlin).seconds()
        val result = reanchorAllDayTimes(local(day, day, berlin, limit), berlin, losAngeles, isSynced = false)
        assertEquals(DateTime(2026, 12, 9, 23, 59, 59, losAngeles).seconds(), result.repeatLimit)

        val midnightLimit = LocalDate(2026, 12, 9).toDateTimeAtStartOfDay(berlin).seconds()
        val midnightResult = reanchorAllDayTimes(local(day, day, berlin, midnightLimit), berlin, auckland, false)
        assertEquals(LocalDate(2026, 12, 9).toDateTimeAtStartOfDay(auckland).seconds(), midnightResult.repeatLimit)

        val repeatCount = -5L
        val countResult = reanchorAllDayTimes(local(day, day, berlin, repeatCount), berlin, auckland, false)
        assertEquals(repeatCount, countResult.repeatLimit)
    }

    @Test
    fun syncedEventsMatchWhatTheNextSyncDerives() {
        val until = LocalDate(2026, 12, 9)
        val cases = listOf(
            LocalDate(2026, 11, 22) to LocalDate(2026, 11, 22),
            LocalDate(2026, 11, 24) to LocalDate(2026, 11, 26),
            LocalDate(2026, 3, 7) to LocalDate(2026, 3, 8),
            LocalDate(2026, 10, 31) to LocalDate(2026, 11, 1),
            LocalDate(2026, 3, 28) to LocalDate(2026, 3, 29),
            LocalDate(2026, 10, 25) to LocalDate(2026, 10, 25),
            LocalDate(2026, 9, 5) to LocalDate(2026, 9, 6),
        )
        val zones = listOf(berlin, losAngeles, auckland, santiago)
        for ((first, last) in cases) {
            for (from in zones) {
                for (to in zones) {
                    val stored = synced(first, last, from, until)
                    val expected = synced(first, last, to, until)
                    val result = reanchorAllDayTimes(stored, from, to, isSynced = true)
                    assertEquals("$first..$last ${from.id} -> ${to.id}", expected, result)
                }
            }
        }
    }

    @Test
    fun syncedUntilDateTimeIsAnAbsoluteInstant() {
        val day = LocalDate(2026, 11, 18)
        val until = DateTime(2026, 12, 9, 22, 59, 59, DateTimeZone.UTC).seconds()
        val stored = synced(day, day, berlin).copy(repeatLimit = until)
        assertEquals(until, reanchorAllDayTimes(stored, berlin, losAngeles, isSynced = true).repeatLimit)
    }

    @Test
    fun syncedZeroLengthEventStaysZeroLength() {
        val day = LocalDate(2026, 11, 22)
        val midnight = day.toDateTimeAtStartOfDay(berlin).seconds()
        val stored = AllDayTimes(midnight, midnight, 0)
        val result = reanchorAllDayTimes(stored, berlin, losAngeles, isSynced = true)
        assertEquals(day.toDateTimeAtStartOfDay(losAngeles).seconds(), result.startTS)
        assertEquals(result.startTS, result.endTS)
    }

    @Test
    fun isIdempotentAndReversible() {
        val first = LocalDate(2026, 11, 24)
        val last = LocalDate(2026, 11, 26)
        val limit = DateTime(2026, 12, 9, 23, 59, 59, berlin).seconds()
        for (isSynced in listOf(false, true)) {
            val stored = if (isSynced) {
                synced(first, last, berlin, LocalDate(2026, 12, 9))
            } else {
                local(first, last, berlin, limit)
            }
            val moved = reanchorAllDayTimes(stored, berlin, auckland, isSynced)
            assertEquals(moved, reanchorAllDayTimes(moved, auckland, auckland, isSynced))
            assertEquals(stored, reanchorAllDayTimes(stored, berlin, berlin, isSynced))
            assertEquals(stored, reanchorAllDayTimes(moved, auckland, berlin, isSynced))
        }
    }
}
