package org.fossify.calendar.helpers

import android.Manifest
import android.content.ContentUris
import android.content.ContentValues
import android.net.Uri
import android.provider.CalendarContract
import android.provider.CalendarContract.Calendars
import android.provider.CalendarContract.Events
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import org.fossify.calendar.extensions.calDAVHelper
import org.fossify.calendar.extensions.calendarsDB
import org.fossify.calendar.extensions.config
import org.fossify.calendar.extensions.eventsDB
import org.fossify.calendar.models.CalendarEntity
import org.fossify.calendar.models.Event
import org.joda.time.DateTime
import org.joda.time.DateTimeZone
import org.joda.time.format.DateTimeFormat
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CalDAVSyncTest {
    @get:Rule
    val permissions: GrantPermissionRule =
        GrantPermissionRule.grant(Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR)

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val accountName = "fossify-caldav-sync-test"
    private val displayName = "CalDAV sync test"
    private val color = 0xFF0000FF.toInt()
    private val marker = 123456789L
    private val start = DateTime.now().plusDays(1).withTime(12, 0, 0, 0)
    private val originalZone = DateTimeZone.getDefault()
    private lateinit var originalSyncedCalendarIds: String
    private var providerCalendarId = 0L
    private var localCalendar: CalendarEntity? = null
    private var parentId = 0L

    @Before
    fun setUp() {
        // a fresh database inserts the local calendar asynchronously, possibly replacing ours
        repeat(50) {
            if (context.calendarsDB.getCalendarWithId(LOCAL_CALENDAR_ID) == null) Thread.sleep(100)
        }
        originalSyncedCalendarIds = context.config.caldavSyncedCalendarIds
        providerCalendarId = ContentUris.parseId(
            context.contentResolver.insert(asSyncAdapter(Calendars.CONTENT_URI), ContentValues().apply {
                put(Calendars.ACCOUNT_NAME, accountName)
                put(Calendars.ACCOUNT_TYPE, CalendarContract.ACCOUNT_TYPE_LOCAL)
                put(Calendars.NAME, displayName)
                put(Calendars.CALENDAR_DISPLAY_NAME, displayName)
                put(Calendars.CALENDAR_COLOR, color)
                put(Calendars.CALENDAR_ACCESS_LEVEL, Calendars.CAL_ACCESS_OWNER)
                put(Calendars.OWNER_ACCOUNT, accountName)
                put(Calendars.SYNC_EVENTS, 1)
                put(Calendars.VISIBLE, 1)
            })!!
        )

        localCalendar = CalendarEntity(
            id = null,
            title = displayName,
            color = color,
            caldavCalendarId = providerCalendarId.toInt(),
            caldavDisplayName = displayName,
            caldavEmail = accountName
        ).apply { id = context.calendarsDB.insertOrUpdate(this) }
        context.config.caldavSyncedCalendarIds = providerCalendarId.toString()

        parentId = insertEvent(
            "Recurring",
            start,
            Events.RRULE to "FREQ=DAILY;COUNT=10",
            Events.EXDATE to start.plusDays(2).withZone(DateTimeZone.UTC).toString(EXDATE_FORMAT)
        )
        insertEvent(
            "Modified",
            start.plusDays(4).plusHours(3),
            Events.ORIGINAL_ID to parentId,
            Events.ORIGINAL_INSTANCE_TIME to start.plusDays(4).millis
        )
        insertEvent(
            "Cancelled",
            start.plusDays(5),
            Events.ORIGINAL_ID to parentId,
            Events.ORIGINAL_INSTANCE_TIME to start.plusDays(5).millis,
            Events.STATUS to Events.STATUS_CANCELED
        )
    }

    @After
    fun tearDown() {
        context.eventsDB.deleteEvents(storedEvents().map { it.id!! })
        localCalendar?.let { context.calendarsDB.deleteCalendars(listOf(it)) }
        context.contentResolver.delete(
            ContentUris.withAppendedId(asSyncAdapter(Calendars.CONTENT_URI), providerCalendarId), null, null
        )
        context.config.caldavSyncedCalendarIds = originalSyncedCalendarIds
        DateTimeZone.setDefault(originalZone)
    }

    @Test
    fun unchangedEventsAreNotRewrittenAndExceptionsAreKept() {
        sync()
        assertEquals(listOf("Modified", "Recurring"), storedEvents().map { it.title }.sorted())
        assertEquals(dayCodes(2, 4, 5), parent().repetitionExceptions.sorted())

        // a rewrite during sync would reset the marker
        storedEvents().forEach { context.eventsDB.insertOrUpdate(it.copy(lastUpdated = marker)) }
        sync()
        assertEquals(listOf(marker, marker), storedEvents().map { it.lastUpdated })
        assertEquals(dayCodes(2, 4, 5), parent().repetitionExceptions.sorted())
    }

    @Test
    fun changedExceptionDatesAreUpdated() {
        sync()
        updateParent(Events.EXDATE to null)
        sync()
        assertEquals(dayCodes(4, 5), parent().repetitionExceptions.sorted())

        updateParent(Events.EXDATE to start.plusDays(6).withZone(DateTimeZone.UTC).toString(EXDATE_FORMAT))
        sync()
        assertEquals(dayCodes(4, 5, 6), parent().repetitionExceptions.sorted())
    }

    @Test
    fun allDayExceptionsAreOnTheirUtcDateWestOfUtc() {
        // all-day occurrences are stored at midnight UTC, which is the evening before in New York
        DateTimeZone.setDefault(DateTimeZone.forID("America/New_York"))
        val day = DateTime.now(DateTimeZone.UTC).plusDays(1).withTimeAtStartOfDay()
        val allDayId = insertEvent(
            "All-day",
            day,
            Events.RRULE to "FREQ=DAILY;COUNT=10",
            Events.EXDATE to day.plusDays(2).toString(EXDATE_FORMAT),
            allDay = true
        )
        insertEvent(
            "All-day moved",
            day.plusDays(6),
            Events.ORIGINAL_ID to allDayId,
            Events.ORIGINAL_INSTANCE_TIME to day.plusDays(4).millis,
            Events.ORIGINAL_ALL_DAY to 1,
            allDay = true
        )
        insertEvent(
            "All-day cancelled",
            day.plusDays(5),
            Events.ORIGINAL_ID to allDayId,
            Events.ORIGINAL_INSTANCE_TIME to day.plusDays(5).millis,
            Events.ORIGINAL_ALL_DAY to 1,
            Events.STATUS to Events.STATUS_CANCELED,
            allDay = true
        )

        val expected = listOf(2, 4, 5).map { day.plusDays(it).toString(Formatter.DAYCODE_PATTERN) }
        val allDayParent = { context.eventsDB.getEventWithImportId("$CALDAV-$providerCalendarId-$allDayId")!! }
        sync()
        assertEquals(expected, allDayParent().repetitionExceptions.sorted())

        // the day codes of the parent and its children must agree, otherwise every sync rewrites the parent
        storedEvents().forEach { context.eventsDB.insertOrUpdate(it.copy(lastUpdated = marker)) }
        sync()
        assertTrue(storedEvents().all { it.lastUpdated == marker })
        assertEquals(expected, allDayParent().repetitionExceptions.sorted())
    }

    private fun sync() {
        // refreshCalendars returns without a callback while another refresh is running
        var synced = false
        repeat(50) {
            if (!synced) {
                context.calDAVHelper.refreshCalendars(showToasts = false, scheduleNextSync = false) { synced = true }
                if (!synced) Thread.sleep(100)
            }
        }
        assertTrue(synced)
    }

    private fun storedEvents() = context.eventsDB.getEventsFromCalDAVCalendar("$CALDAV-$providerCalendarId")

    private fun parent(): Event = context.eventsDB.getEventWithImportId("$CALDAV-$providerCalendarId-$parentId")!!

    private fun dayCodes(vararg days: Int) = days.map { Formatter.getDayCodeFromDateTime(start.plusDays(it)) }

    private fun insertEvent(
        title: String,
        start: DateTime,
        vararg values: Pair<String, Any?>,
        allDay: Boolean = false
    ): Long {
        val contentValues = eventValues(*values).apply {
            put(Events.CALENDAR_ID, providerCalendarId)
            put(Events.TITLE, title)
            put(Events.EVENT_TIMEZONE, if (allDay) "UTC" else DateTimeZone.getDefault().id)
            put(Events.ALL_DAY, if (allDay) 1 else 0)
            put(Events.DTSTART, start.millis)
            if (containsKey(Events.RRULE)) {
                put(Events.DURATION, if (allDay) "P1D" else "PT1H")
            } else {
                put(Events.DTEND, if (allDay) start.plusDays(1).millis else start.plusHours(1).millis)
            }
        }
        return ContentUris.parseId(context.contentResolver.insert(asSyncAdapter(Events.CONTENT_URI), contentValues)!!)
    }

    private fun updateParent(vararg values: Pair<String, Any?>) {
        val uri = ContentUris.withAppendedId(asSyncAdapter(Events.CONTENT_URI), parentId)
        context.contentResolver.update(uri, eventValues(*values), null, null)
    }

    private fun eventValues(vararg values: Pair<String, Any?>) = ContentValues().apply {
        values.forEach { (key, value) ->
            when (value) {
                null -> putNull(key)
                is Long -> put(key, value)
                is Int -> put(key, value)
                else -> put(key, value.toString())
            }
        }
    }

    private fun asSyncAdapter(uri: Uri): Uri = uri.buildUpon()
        .appendQueryParameter(CalendarContract.CALLER_IS_SYNCADAPTER, "true")
        .appendQueryParameter(Calendars.ACCOUNT_NAME, accountName)
        .appendQueryParameter(Calendars.ACCOUNT_TYPE, CalendarContract.ACCOUNT_TYPE_LOCAL)
        .build()

    companion object {
        private val EXDATE_FORMAT = DateTimeFormat.forPattern("yyyyMMdd'T'HHmmss'Z'")
    }
}
