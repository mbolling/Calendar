package org.fossify.calendar.databases

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.fossify.calendar.extensions.seconds
import org.fossify.calendar.helpers.FLAG_ALL_DAY
import org.fossify.calendar.helpers.TWELVE_HOURS
import org.fossify.calendar.helpers.TYPE_TASK
import org.fossify.calendar.models.Event
import org.joda.time.DateTime
import org.joda.time.DateTimeZone
import org.joda.time.LocalDate
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ReanchorAllDayEventsTest {
    private val berlin = DateTimeZone.forID("Europe/Berlin")
    private val losAngeles = DateTimeZone.forID("America/Los_Angeles")
    private val first = LocalDate(2026, 11, 24)
    private val last = LocalDate(2026, 11, 26)

    private lateinit var db: EventsDatabase

    @Before
    fun createDb() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, EventsDatabase::class.java).build()
    }

    @After
    fun closeDb() {
        db.close()
    }

    private fun allDay(zone: DateTimeZone, timeZone: String = zone.id, source: String = "simple-calendar") = Event(
        id = null,
        startTS = first.toDateTimeAtStartOfDay(zone).seconds(),
        endTS = last.toDateTimeAtStartOfDay(zone).withHourOfDay(12).seconds(),
        timeZone = timeZone,
        flags = FLAG_ALL_DAY,
        source = source,
        lastUpdated = 1234L
    )

    private fun insert(event: Event) = db.EventsDao().insertOrUpdate(event)

    private fun reanchor(zone: DateTimeZone) = EventsDatabase.reanchorAllDayEvents(db.openHelper.writableDatabase, zone)

    @Test
    fun movesAllDayEventsStoredInAnotherZone() {
        val limit = DateTime(2026, 12, 9, 23, 59, 59, berlin).seconds()
        val id = insert(allDay(berlin).copy(repeatInterval = 604800, repeatRule = 4, repeatLimit = limit))

        assertTrue(reanchor(losAngeles))

        val moved = db.EventsDao().getEventOrTaskWithId(id)!!
        assertEquals(first.toDateTimeAtStartOfDay(losAngeles).seconds(), moved.startTS)
        assertEquals(last.toDateTimeAtStartOfDay(losAngeles).withHourOfDay(12).seconds(), moved.endTS)
        assertEquals(DateTime(2026, 12, 9, 23, 59, 59, losAngeles).seconds(), moved.repeatLimit)
        assertEquals(losAngeles.id, moved.timeZone)
        assertEquals(1234L, moved.lastUpdated)
    }

    @Test
    fun movesSyncedEventsToWhatTheSyncDerives() {
        val syncedEnd = last.plusDays(1).toDateTimeAtStartOfDay(berlin).seconds() - TWELVE_HOURS
        val id = insert(allDay(berlin, source = "Caldav-1").copy(endTS = syncedEnd))

        assertTrue(reanchor(losAngeles))

        val moved = db.EventsDao().getEventOrTaskWithId(id)!!
        assertEquals(first.toDateTimeAtStartOfDay(losAngeles).seconds(), moved.startTS)
        assertEquals(last.plusDays(1).toDateTimeAtStartOfDay(losAngeles).seconds() - TWELVE_HOURS, moved.endTS)
    }

    @Test
    fun onlyStampsEventsWithoutAKnownZone() {
        val empty = allDay(berlin, timeZone = "")
        val invalid = allDay(berlin, timeZone = "Not/AZone")
        val emptyId = insert(empty)
        val invalidId = insert(invalid)

        assertTrue(reanchor(losAngeles))

        for ((id, original) in listOf(emptyId to empty, invalidId to invalid)) {
            val stamped = db.EventsDao().getEventOrTaskWithId(id)!!
            assertEquals(original.startTS, stamped.startTS)
            assertEquals(original.endTS, stamped.endTS)
            assertEquals(losAngeles.id, stamped.timeZone)
        }
    }

    @Test
    fun leavesTimedEventsAndTasksAlone() {
        val timed = allDay(berlin).copy(flags = 0)
        val task = allDay(berlin).copy(type = TYPE_TASK)
        val timedId = insert(timed)
        val taskId = insert(task)

        assertFalse(reanchor(losAngeles))

        assertEquals(timed.copy(id = timedId), db.EventsDao().getEventOrTaskWithId(timedId))
        assertEquals(task.copy(id = taskId), db.EventsDao().getEventOrTaskWithId(taskId))
    }

    @Test
    fun isIdempotent() {
        val id = insert(allDay(berlin))
        assertTrue(reanchor(losAngeles))
        val moved = db.EventsDao().getEventOrTaskWithId(id)

        assertFalse(reanchor(losAngeles))
        assertEquals(moved, db.EventsDao().getEventOrTaskWithId(id))

        assertTrue(reanchor(berlin))
        assertEquals(allDay(berlin).copy(id = id), db.EventsDao().getEventOrTaskWithId(id))
    }
}
