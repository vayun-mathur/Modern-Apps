package com.vayunmathur.todo.sync

import android.Manifest
import android.app.Application
import android.content.ContentProvider
import android.content.ContentProviderOperation
import android.content.ContentProviderResult
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.provider.CalendarContract
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.todo.data.Todo
import com.vayunmathur.todo.domain.DueDates
import com.vayunmathur.todo.domain.Recurrence
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowContentResolver
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * In-memory CalendarProvider stand-in under the real calendar authority, so
 * [CalendarMirror]'s full write path (create calendar, insert/update/delete
 * events, delete calendar) runs under Robolectric. Same pattern as the
 * calendar app's FakeCalendarProvider; state is asserted through the provider.
 */
class FakeCalendarProvider : ContentProvider() {
    val calendarIds = mutableListOf<Long>()
    val events = linkedMapOf<Long, ContentValues>()
    var nextCalendarId = 42L
    var nextEventId = 100L
    var insertCount = 0
    var updateCount = 0
    var deleteCount = 0
    var calendarDeleteCount = 0
    var throwOnQuery = false

    override fun onCreate(): Boolean = true
    override fun getType(uri: Uri): String? = null

    override fun query(
        uri: Uri, projection: Array<out String>?, selection: String?,
        selectionArgs: Array<out String>?, sortOrder: String?,
    ): Cursor {
        if (throwOnQuery) throw IllegalStateException("provider boom")
        val cols = projection ?: emptyArray()
        val cursor = MatrixCursor(cols)
        when (uri.pathSegments.firstOrNull()) {
            "calendars" -> calendarIds.forEach { id ->
                cursor.addRow(cols.map { if (it == CalendarContract.Calendars._ID) id else null })
            }
            "events" -> events.forEach { (id, values) ->
                cursor.addRow(cols.map { col ->
                    when (col) {
                        CalendarContract.Events._ID -> id
                        CalendarContract.Events._SYNC_ID -> values.getAsString(CalendarContract.Events._SYNC_ID)
                        CalendarContract.Events.SYNC_DATA1 -> values.getAsString(CalendarContract.Events.SYNC_DATA1)
                        else -> values.get(col)
                    }
                })
            }
        }
        return cursor
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri {
        val copy = ContentValues(values)
        return when (uri.pathSegments.firstOrNull()) {
            "calendars" -> {
                val id = nextCalendarId++
                calendarIds += id
                ContentUris.withAppendedId(uri, id)
            }
            else -> {
                val id = nextEventId++
                events[id] = copy
                insertCount++
                ContentUris.withAppendedId(uri, id)
            }
        }
    }

    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int {
        val id = ContentUris.parseId(uri)
        if (events.containsKey(id)) {
            events[id] = ContentValues(values)
            updateCount++
            return 1
        }
        return 0
    }

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int =
        when (uri.pathSegments.firstOrNull()) {
            "calendars" -> {
                calendarIds.remove(ContentUris.parseId(uri))
                calendarDeleteCount++
                1
            }
            else -> {
                if (events.remove(ContentUris.parseId(uri)) != null) { deleteCount++; 1 } else 0
            }
        }

    override fun applyBatch(operations: ArrayList<ContentProviderOperation>): Array<ContentProviderResult> {
        val results = ArrayList<ContentProviderResult>(operations.size)
        for ((index, op) in operations.withIndex()) {
            results += when {
                op.isDelete -> ContentProviderResult(delete(op.uri, null, null))
                op.isUpdate -> ContentProviderResult(
                    update(op.uri, op.resolveValueBackReferences(results.toTypedArray(), index), null, null),
                )
                else -> {
                    insert(op.uri, op.resolveValueBackReferences(results.toTypedArray(), index))
                    ContentProviderResult(1)
                }
            }
        }
        return results.toTypedArray()
    }
}

@RunWith(RobolectricTestRunner::class)
class CalendarMirrorProviderTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val app: Application get() = ApplicationProvider.getApplicationContext()
    private lateinit var provider: FakeCalendarProvider

    @BeforeTest fun setUp() {
        provider = FakeCalendarProvider()
        ShadowContentResolver.registerProviderInternal(CalendarContract.AUTHORITY, provider)
        shadowOf(app).grantPermissions(Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR)
    }

    private val zone = TimeZone.currentSystemDefault()
    private fun allDayTodo(syncId: String, title: String) = Todo(
        syncId = syncId, title = title,
        dueAt = DueDates.startOfDay(LocalDate(2026, 10, 20), zone), dueAllDay = true,
    )

    @Test fun reconcileCreatesCalendarAndWritesOnlyMirroredTasks() {
        val timed = System.currentTimeMillis() + 3_600_000L
        CalendarMirror.reconcile(
            context, enabled = true,
            todos = listOf(
                Todo(syncId = "t1", title = "Timed", notes = "note", dueAt = timed, dueAllDay = false),
                allDayTodo("t2", "All day"),
                Todo(syncId = "t3", title = "Daily", dueAt = timed, dueAllDay = false, recurrence = Recurrence.Daily(2).serialize()),
                Todo(syncId = "done", title = "Done", dueAt = timed, done = true, completedAt = timed),
                Todo(syncId = "del", title = "Deleted", dueAt = timed, deleted = true),
                Todo(syncId = "nodate", title = "No date"),
                Todo(syncId = "sub", title = "Sub", dueAt = timed, parentSyncId = "t1"),
            ),
        )
        assertEquals(listOf(42L), provider.calendarIds)
        assertEquals(3, provider.events.size)
        val bySync = provider.events.values.associateBy { it.getAsString(CalendarContract.Events._SYNC_ID) }
        assertEquals(setOf("t1", "t2", "t3"), bySync.keys)
        assertEquals("Timed", bySync["t1"]!!.getAsString(CalendarContract.Events.TITLE))
        assertEquals("note", bySync["t1"]!!.getAsString(CalendarContract.Events.DESCRIPTION))
        assertEquals(timed, bySync["t1"]!!.getAsLong(CalendarContract.Events.DTSTART))
        assertEquals(timed + 30 * 60_000L, bySync["t1"]!!.getAsLong(CalendarContract.Events.DTEND))
        assertEquals(0, bySync["t1"]!!.getAsInteger(CalendarContract.Events.ALL_DAY))
        assertEquals(1, bySync["t2"]!!.getAsInteger(CalendarContract.Events.ALL_DAY))
        assertEquals("UTC", bySync["t2"]!!.getAsString(CalendarContract.Events.EVENT_TIMEZONE))
        // All-day duration is one day from its UTC-midnight start.
        assertEquals(
            bySync["t2"]!!.getAsLong(CalendarContract.Events.DTSTART) + 86_400_000L,
            bySync["t2"]!!.getAsLong(CalendarContract.Events.DTEND),
        )
        // Recurring: RRULE + DURATION, no DTEND value.
        assertNotNull(bySync["t3"]!!.getAsString(CalendarContract.Events.RRULE))
        assertEquals("PT30M", bySync["t3"]!!.getAsString(CalendarContract.Events.DURATION))
        assertTrue(bySync.values.all { it.getAsString(CalendarContract.Events.SYNC_DATA1) != null })
        assertTrue(bySync.values.all { it.getAsLong(CalendarContract.Events.CALENDAR_ID) == 42L })
    }

    @Test fun reconcileSecondRunIsStableThenUpdatesAndDeletesChangedRows() {
        val timed = System.currentTimeMillis() + 3_600_000L
        val todos = listOf(
            Todo(syncId = "a", title = "Alpha", dueAt = timed, dueAllDay = false),
            Todo(syncId = "b", title = "Beta", dueAt = timed, dueAllDay = false),
        )
        CalendarMirror.reconcile(context, enabled = true, todos = todos)
        assertEquals(2, provider.insertCount)
        // Unchanged: fingerprints match, so no further writes.
        CalendarMirror.reconcile(context, enabled = true, todos = todos)
        assertEquals(2, provider.insertCount)
        assertEquals(0, provider.updateCount)
        assertEquals(0, provider.deleteCount)
        // Changed title updates in place; dropping "b" deletes its event.
        CalendarMirror.reconcile(
            context, enabled = true,
            todos = listOf(Todo(syncId = "a", title = "Alpha renamed", dueAt = timed, dueAllDay = false)),
        )
        assertEquals(1, provider.updateCount)
        assertEquals(1, provider.deleteCount)
        assertEquals(2, provider.insertCount)
        assertEquals(1, provider.events.size)
        assertEquals(
            "Alpha renamed",
            provider.events.values.single().getAsString(CalendarContract.Events.TITLE),
        )
    }

    @Test fun reconcileReusesExistingCalendarInsteadOfCreatingAnother() {
        provider.calendarIds += 7L
        val timed = System.currentTimeMillis() + 3_600_000L
        CalendarMirror.reconcile(
            context, enabled = true,
            todos = listOf(Todo(syncId = "a", title = "Alpha", dueAt = timed, dueAllDay = false)),
        )
        assertEquals(listOf(7L), provider.calendarIds)
        assertEquals(7L, provider.events.values.single().getAsLong(CalendarContract.Events.CALENDAR_ID))
    }

    @Test fun reconcileDisabledDeletesCalendarAndDisabledWithoutCalendarIsNoOp() {
        val timed = System.currentTimeMillis() + 3_600_000L
        CalendarMirror.reconcile(
            context, enabled = true,
            todos = listOf(Todo(syncId = "a", title = "Alpha", dueAt = timed, dueAllDay = false)),
        )
        assertEquals(1, provider.calendarIds.size)
        CalendarMirror.reconcile(context, enabled = false, todos = emptyList())
        assertTrue(provider.calendarIds.isEmpty())
        assertEquals(1, provider.calendarDeleteCount)
        // Already gone: nothing more to delete.
        CalendarMirror.reconcile(context, enabled = false, todos = emptyList())
        assertEquals(1, provider.calendarDeleteCount)
    }

    @Test fun reconcileSwallowsProviderFailures() {
        provider.throwOnQuery = true
        // Must not throw even though every provider query fails.
        CalendarMirror.reconcile(
            context, enabled = true,
            todos = listOf(Todo(syncId = "a", title = "Alpha", dueAt = System.currentTimeMillis(), dueAllDay = false)),
        )
        assertTrue(provider.events.isEmpty())
        assertNull(provider.calendarIds.singleOrNull())
    }
}
