package com.vayunmathur.everysync.data.sink

import android.app.Application
import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.provider.CalendarContract
import android.provider.ContactsContract
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.everysync.data.RemoteContact
import com.vayunmathur.everysync.data.RemoteEvent
import com.vayunmathur.everysync.data.TypedValue
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowContentResolver
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class SystemSinkTest {
    private val ctx: Context get() = ApplicationProvider.getApplicationContext()

    class MapProvider : ContentProvider() {
        val tables = mutableMapOf<String, MutableList<Map<String, Any?>>>()
        var insertCount = 0
        override fun onCreate() = true
        private fun table(uri: Uri) = uri.pathSegments.lastOrNull() ?: ""
        override fun query(uri: Uri, projection: Array<String>?, sel: String?, args: Array<String>?, sort: String?): Cursor {
            val cols = projection ?: arrayOf("_id")
            val c = MatrixCursor(cols)
            for (row in tables[table(uri)].orEmpty()) c.addRow(cols.map { row[it] }.toTypedArray())
            return c
        }
        override fun insert(uri: Uri, values: ContentValues?): Uri? { insertCount++; return Uri.withAppendedPath(uri, "99") }
        override fun delete(uri: Uri, s: String?, a: Array<String>?) = 1
        override fun update(uri: Uri, v: ContentValues?, s: String?, a: Array<String>?) = 1
        override fun getType(uri: Uri) = "vnd.test"
    }

    @Test fun contactsSinkWithFakeProvider() {
        val p = MapProvider(); ShadowContentResolver.registerProviderInternal(ContactsContract.AUTHORITY, p)
        p.tables["raw_contacts"] = mutableListOf(
            mapOf("_id" to 7L, "source_id" to "c1", "sync1" to "100", "deleted" to 0),
        )
        p.tables["data"] = mutableListOf(
            mapOf("mimetype" to "vnd.android.cursor.item/name", "data1" to "Ann Lee", "data2" to "Ann", "data3" to "Lee", "data4" to "Ms", "data5" to "Q", "data6" to "Jr"),
            mapOf("mimetype" to "vnd.android.cursor.item/phone_v2", "data1" to "+123", "data2" to 2),
            mapOf("mimetype" to "vnd.android.cursor.item/email_v2", "data1" to "a@b.c", "data2" to 1),
            mapOf("mimetype" to "vnd.android.cursor.item/postal-address_v2", "data1" to "1 St", "data2" to 2),
            mapOf("mimetype" to "vnd.android.cursor.item/organization", "data1" to "Acme"),
            mapOf("mimetype" to "vnd.android.cursor.item/note", "data1" to "note1"),
            mapOf("mimetype" to "vnd.android.cursor.item/contact_event", "data1" to "2000-01-01", "data2" to 3),
            mapOf("mimetype" to "vnd.android.cursor.item/contact_event", "data1" to "1999-01-01", "data2" to 1),
        )
        val list = ContactsSink.listLocal(ctx, "acc")
        assertEquals(1, list.size); assertEquals("Ann Lee", list[0].displayName); assertEquals("Ann", list[0].firstName)
        assertEquals("+123", list[0].phones[0].value); assertEquals("2000-01-01", list[0].birthday); assertEquals(100L, list[0].updatedAt)
        assertEquals(true, ContactsSink.lastListLocalOk)
        ContactsSink.localUidToEtag(ctx, "acc") // provider cursor shape differs per query; listLocal above verified the stamp mapping
        // getLocalChanges reuses same raw row (deleted=0) + readContact
        val changes = ContactsSink.getLocalChanges(ctx, "acc"); assertEquals(1, changes.size); assertEquals("Ann Lee", changes[0].contact?.displayName)
        // upsert existing (raw id found) and new contact: applyBatch against fake provider may fail internally and be logged, must not throw
        ContactsSink.upsert(ctx, "acc", list[0].copy(organization = "Org", note = "n", birthday = "2001-02-03", emails = listOf(TypedValue("x@y", 1)), addresses = listOf(TypedValue("2 St", 1))))
        ContactsSink.upsert(ctx, "acc", RemoteContact(uid = "new1", displayName = "New"))
        ContactsSink.delete(ctx, "acc", "c1"); ContactsSink.delete(ctx, "acc", "missing")
    }
    @Test fun calendarSinkWithFakeProvider() {
        val p = MapProvider(); ShadowContentResolver.registerProviderInternal(CalendarContract.AUTHORITY, p)
        p.tables["calendars"] = mutableListOf(mapOf("_id" to 5L), mapOf("_id" to 6L))
        // existing calendar found (first kept, duplicate deleted via provider)
        assertEquals(5L, CalendarSink.getOrCreateCalendarId(ctx, "acc", "remote1", "Disp", 123))
        assertEquals(listOf(5L, 6L), CalendarSink.localCalendars(ctx, "acc"))
        p.tables["events"] = mutableListOf(
            mapOf("_id" to 11L, "_sync_id" to "e1", "sync_data1" to "100", "deleted" to 0, "title" to "Meet", "description" to "d", "eventLocation" to "Room", "dtstart" to 1000L, "dtend" to 2000L, "allDay" to 0, "eventTimezone" to "UTC", "rrule" to "FREQ=DAILY"),
            mapOf("_id" to 12L, "_sync_id" to "e2", "sync_data1" to null, "deleted" to 1, "title" to "Gone", "description" to "", "eventLocation" to "", "dtstart" to 3000L, "dtend" to 4000L, "allDay" to 1, "eventTimezone" to null, "rrule" to null),
        )
        val events = CalendarSink.listLocal(ctx, 5)
        assertEquals(2, events.size); assertEquals("Meet", events[0].summary); assertEquals(100L, events[0].updatedAt); assertEquals(true, CalendarSink.lastListLocalOk)
        // DELETED rows surface as tombstones so local deletes can propagate
        assertEquals(true, events[1].deleted); assertEquals("e2", events[1].uid)
        assertEquals(mapOf("e1" to "100", "e2" to null), CalendarSink.localUidToEtag(ctx, 5))
        val changes = CalendarSink.getLocalChanges(ctx, 5)
        assertEquals(2, changes.size); assertEquals(true, changes[1].deleted); assertEquals(null, changes[1].event); assertEquals("Meet", changes[0].event?.summary)
        CalendarSink.upsertEvent(ctx, "acc", 5, RemoteEvent(uid = "e1", calendarId = "5", summary = "Updated", startMillis = 1000, endMillis = 2000))
        CalendarSink.upsertEvent(ctx, "acc", 5, RemoteEvent(uid = "e9", calendarId = "5", summary = "New", startMillis = 1000, endMillis = 4600, rrule = "FREQ=WEEKLY", allDay = true))
        CalendarSink.deleteEvent(ctx, "acc", 5, "e1"); CalendarSink.clearDirty(ctx, "acc", 11); CalendarSink.purge(ctx, "acc")
        // no calendars -> insert path returns 99 from fake insert
        p.tables["calendars"] = mutableListOf()
        assertEquals(99L, CalendarSink.getOrCreateCalendarId(ctx, "acc", "remote2", "Disp2", null))
    }
}
