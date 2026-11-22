package org.fossify.calendar.databases

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import org.fossify.calendar.R
import org.fossify.calendar.extensions.config
import org.fossify.calendar.helpers.AllDayTimes
import org.fossify.calendar.helpers.CALDAV
import org.fossify.calendar.helpers.Converters
import org.fossify.calendar.helpers.FLAG_ALL_DAY
import org.fossify.calendar.helpers.LOCAL_CALENDAR_ID
import org.fossify.calendar.helpers.TYPE_EVENT
import org.fossify.calendar.helpers.reanchorAllDayTimes
import org.fossify.calendar.interfaces.CalendarsDao
import org.fossify.calendar.interfaces.EventsDao
import org.fossify.calendar.interfaces.TasksDao
import org.fossify.calendar.interfaces.WidgetsDao
import org.fossify.calendar.models.CalendarEntity
import org.fossify.calendar.models.Event
import org.fossify.calendar.models.Task
import org.fossify.calendar.models.Widget
import org.fossify.commons.extensions.getLongValue
import org.fossify.commons.extensions.getProperPrimaryColor
import org.fossify.commons.extensions.getStringValue
import org.joda.time.DateTimeZone
import java.util.concurrent.Executors

@Database(
    entities = [Event::class, CalendarEntity::class, Widget::class, Task::class],
    version = 11
)
@TypeConverters(Converters::class)
abstract class EventsDatabase : RoomDatabase() {

    abstract fun EventsDao(): EventsDao

    abstract fun CalendarsDao(): CalendarsDao

    abstract fun WidgetsDao(): WidgetsDao

    abstract fun TasksDao(): TasksDao

    companion object {
        private var db: EventsDatabase? = null

        fun getInstance(context: Context): EventsDatabase {
            if (db == null) {
                synchronized(EventsDatabase::class) {
                    if (db == null) {
                        db = Room.databaseBuilder(
                            context = context.applicationContext,
                            klass = EventsDatabase::class.java,
                            name = "events.db"
                        )
                            .addCallback(object : Callback() {
                                override fun onCreate(db: SupportSQLiteDatabase) {
                                    super.onCreate(db)
                                    insertLocalCalendar(context)
                                }

                                // runs before the first query of the process returns
                                override fun onOpen(db: SupportSQLiteDatabase) {
                                    super.onOpen(db)
                                    reanchorAllDayEvents(db)
                                }
                            })
                            .addMigrations(MIGRATION_1_2)
                            .addMigrations(MIGRATION_2_3)
                            .addMigrations(MIGRATION_3_4)
                            .addMigrations(MIGRATION_4_5)
                            .addMigrations(MIGRATION_5_6)
                            .addMigrations(MIGRATION_6_7)
                            .addMigrations(MIGRATION_7_8)
                            .addMigrations(MIGRATION_8_9)
                            .addMigrations(MIGRATION_9_10)
                            .addMigrations(MIGRATION_10_11)
                            .build()
                        db!!.openHelper.setWriteAheadLoggingEnabled(true)
                    }
                }
            }
            return db!!
        }

        fun destroyInstance() {
            db = null
        }

        /**
         * All-day events are stored anchored in the time zone they were saved in, but read in the current one. Moves
         * those stored in another zone to the same dates in [zone]. Writes directly, nothing is pushed to CalDAV, as
         * the next sync derives the same values. Returns true if any event changed.
         */
        fun reanchorAllDayEvents(db: SupportSQLiteDatabase, zone: DateTimeZone = DateTimeZone.getDefault()): Boolean {
            val updates = ArrayList<Array<Any>>()
            db.query(
                "SELECT id, start_ts, end_ts, repeat_limit, time_zone, source FROM events " +
                    "WHERE (flags & $FLAG_ALL_DAY) != 0 AND type = $TYPE_EVENT AND time_zone != ?",
                arrayOf(zone.id)
            ).use { cursor ->
                while (cursor.moveToNext()) {
                    val times = AllDayTimes(
                        startTS = cursor.getLongValue("start_ts"),
                        endTS = cursor.getLongValue("end_ts"),
                        repeatLimit = cursor.getLongValue("repeat_limit")
                    )
                    val storedZone = try {
                        DateTimeZone.forID(cursor.getStringValue("time_zone"))
                    } catch (_: IllegalArgumentException) {
                        null
                    }

                    // events without a known zone cannot be moved, only start tracking them from now on
                    val newTimes = if (storedZone == null) {
                        times
                    } else {
                        val isSynced = cursor.getStringValue("source").startsWith(CALDAV)
                        reanchorAllDayTimes(times, storedZone, zone, isSynced)
                    }
                    val id = cursor.getLongValue("id")
                    updates.add(arrayOf(newTimes.startTS, newTimes.endTS, newTimes.repeatLimit, zone.id, id))
                }
            }

            if (updates.isEmpty()) {
                return false
            }

            db.beginTransaction()
            try {
                updates.forEach {
                    db.execSQL(
                        "UPDATE events SET start_ts = ?, end_ts = ?, repeat_limit = ?, time_zone = ? WHERE id = ?",
                        it
                    )
                }
                db.setTransactionSuccessful()
            } finally {
                db.endTransaction()
            }
            return true
        }

        private fun insertLocalCalendar(context: Context) {
            Executors.newSingleThreadScheduledExecutor().execute {
                val calendar = CalendarEntity(
                    id = LOCAL_CALENDAR_ID,
                    title = context.resources.getString(R.string.regular_event),
                    color = context.getProperPrimaryColor()
                )
                db!!.CalendarsDao().insertOrUpdate(calendar)
                context.config.addDisplayCalendar(LOCAL_CALENDAR_ID.toString())
            }
        }

        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.apply {
                    execSQL("ALTER TABLE events ADD COLUMN reminder_1_type INTEGER NOT NULL DEFAULT 0")
                    execSQL("ALTER TABLE events ADD COLUMN reminder_2_type INTEGER NOT NULL DEFAULT 0")
                    execSQL("ALTER TABLE events ADD COLUMN reminder_3_type INTEGER NOT NULL DEFAULT 0")
                    execSQL("ALTER TABLE events ADD COLUMN attendees TEXT NOT NULL DEFAULT ''")
                }
            }
        }

        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.apply {
                    execSQL("ALTER TABLE events ADD COLUMN time_zone TEXT NOT NULL DEFAULT ''")
                }
            }
        }

        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.apply {
                    execSQL("ALTER TABLE events ADD COLUMN availability INTEGER NOT NULL DEFAULT 0")
                }
            }
        }

        private val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.apply {
                    execSQL("CREATE TABLE IF NOT EXISTS `widgets` (`id` INTEGER PRIMARY KEY AUTOINCREMENT, `widget_id` INTEGER NOT NULL, `period` INTEGER NOT NULL)")
                    execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_widgets_widget_id` ON `widgets` (`widget_id`)")
                }
            }
        }

        private val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.apply {
                    execSQL("ALTER TABLE events ADD COLUMN type INTEGER NOT NULL DEFAULT 0")
                }
            }
        }

        private val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.apply {
                    execSQL("CREATE TABLE IF NOT EXISTS `tasks` (`id` INTEGER PRIMARY KEY AUTOINCREMENT, `task_id` INTEGER NOT NULL, `start_ts` INTEGER NOT NULL, `flags` INTEGER NOT NULL)")
                    execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_tasks_id_task_id` ON `tasks` (`id`, `task_id`)")
                }
            }
        }

        private val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.apply {
                    execSQL("ALTER TABLE event_types ADD COLUMN type INTEGER NOT NULL DEFAULT 0")
                }
            }
        }

        private val MIGRATION_8_9 = object : Migration(8, 9) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.apply {
                    execSQL("ALTER TABLE events ADD COLUMN status INTEGER NOT NULL DEFAULT 1")
                }
            }
        }

        private val MIGRATION_9_10 = object : Migration(9, 10) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.apply {
                    // remove old, invalid entries
                    execSQL("DELETE FROM `tasks` WHERE `task_id` NOT IN (SELECT e.id FROM events AS e)")
                    // SQLite doesn't support ALTER TABLE ADD CONSTRAINT, so we need to recreate the table
                    execSQL("ALTER TABLE `tasks` RENAME TO `_tasks_tmp`")
                    execSQL("DROP INDEX `index_tasks_id_task_id`")
                    execSQL("CREATE TABLE `tasks` (`id` INTEGER PRIMARY KEY AUTOINCREMENT, `task_id` INTEGER NOT NULL, `start_ts` INTEGER NOT NULL, `flags` INTEGER NOT NULL, CONSTRAINT fk_task_id FOREIGN KEY (task_id) REFERENCES events(id) ON DELETE CASCADE)")
                    execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_tasks_id_task_id` ON `tasks` (`id`, `task_id`)")
                    execSQL("INSERT INTO `tasks` SELECT * FROM `_tasks_tmp`")
                    execSQL("DROP TABLE `_tasks_tmp`")
                }
            }
        }

        private val MIGRATION_10_11 = object : Migration(10, 11) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.apply {
                    execSQL("ALTER TABLE widgets ADD COLUMN header INTEGER NOT NULL DEFAULT 1")
                    execSQL("ALTER TABLE events ADD COLUMN access_level INTEGER NOT NULL DEFAULT 0")
                }
            }
        }
    }
}
