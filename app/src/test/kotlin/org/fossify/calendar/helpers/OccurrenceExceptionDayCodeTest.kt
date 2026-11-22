package org.fossify.calendar.helpers

import org.joda.time.DateTime
import org.joda.time.DateTimeZone
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test

class OccurrenceExceptionDayCodeTest {
    private val originalZone = DateTimeZone.getDefault()

    // midnight UTC of 2026-12-16, how calendar providers store an all-day occurrence of that day
    private val utcMidnight = DateTime(2026, 12, 16, 0, 0, DateTimeZone.UTC).millis / 1000L

    @After
    fun restoreZone() {
        DateTimeZone.setDefault(originalZone)
    }

    @Test
    fun allDayOccurrenceIsOnItsUtcDateInEveryZone() {
        ZONES.forEach { zone ->
            DateTimeZone.setDefault(DateTimeZone.forID(zone))
            assertEquals(zone, "20261216", Formatter.getOccurrenceDayCode(utcMidnight, isAllDay = true))
        }

        // a timed occurrence at the same instant is on its local date
        DateTimeZone.setDefault(DateTimeZone.forID("America/New_York"))
        assertEquals("20261215", Formatter.getOccurrenceDayCode(utcMidnight, isAllDay = false))
    }

    @Test
    fun allDayExdateIsOnItsUtcDateInEveryZone() {
        ZONES.forEach { zone ->
            DateTimeZone.setDefault(DateTimeZone.forID(zone))
            assertEquals(zone, "20261216", Parser().parseExdateDayCode("20261216T000000Z", isAllDay = true))
            assertEquals(zone, "20261216", Parser().parseExdateDayCode("20261216T000000", isAllDay = true))
            assertEquals(zone, "20261216", Parser().parseExdateDayCode("20261216", isAllDay = true))
        }
    }

    companion object {
        private val ZONES = listOf(
            "Pacific/Pago_Pago",
            "Pacific/Honolulu",
            "America/Los_Angeles",
            "America/New_York",
            "America/Sao_Paulo",
            "UTC",
            "Europe/Berlin",
            "Asia/Kolkata",
            "Pacific/Auckland",
            "Pacific/Kiritimati"
        )
    }
}
