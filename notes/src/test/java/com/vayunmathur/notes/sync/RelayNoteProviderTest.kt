package com.vayunmathur.notes.sync

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.notes.data.Note
import com.vayunmathur.notes.seedNotesDb
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
class RelayNoteProviderTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val repo get() = seedNotesDb(context)
    private val provider: RelayNoteProvider by lazy {
        Robolectric.buildContentProvider(RelayNoteProvider::class.java).create().get()
    }
    private val notesUri: Uri get() = RelayNoteProvider.notesUri("vault1")
    private val snapsUri: Uri get() = RelayNoteProvider.snapshotsUri("vault1")

    @BeforeTest fun purge() = runBlocking { repo.purge(); repo.deleteAllSnapshots() }

    private fun payload(uid: String, title: String, updatedAt: Long = 5000, deleted: Boolean = false): ContentValues {
        val json = Json.encodeToString(RelayNoteJson.serializer(), RelayNoteJson(uid = uid, title = title, content = "body-$title", updatedAt = updatedAt, deleted = deleted))
        return ContentValues().apply { put(RelayNoteProvider.COL_PAYLOAD_JSON, json) }
    }

    @Test fun uriBuildersAndTypes() {
        assertEquals("content://com.vayunmathur.notes.relay/vault/v9/notes", RelayNoteProvider.notesUri("v9").toString())
        assertEquals("content://com.vayunmathur.notes.relay/vault/v9/snapshots", RelayNoteProvider.snapshotsUri("v9").toString())
        assertTrue(provider.onCreate())
        assertEquals("vnd.android.cursor.dir/vnd.com.vayunmathur.notes.note", provider.getType(notesUri))
        assertEquals("vnd.android.cursor.dir/vnd.com.vayunmathur.notes.snapshot", provider.getType(snapsUri))
        assertNull(provider.getType(Uri.parse("content://com.vayunmathur.notes.relay/other")))
        assertEquals(arrayOf("syncId", "updatedAt", "deleted", "payloadJson", "contentHash").toList(), RelayNoteProvider.COLUMNS.toList())
    }

    @Test fun insertQueryUpdateDeleteNoteLifecycle() {
        val inserted = provider.insert(notesUri, payload("uid1", "Alpha"))
        assertNotNull(inserted)
        assertEquals("Alpha", runBlocking { repo.getBySyncId("uid1")?.title })
        // re-insert same uid rebinds to same row (no duplicate)
        provider.insert(Uri.parse("$notesUri/upsertRaw"), payload("uid1", "Alpha2", updatedAt = 6000))
        assertEquals(1, runBlocking { repo.getAll() }.size)
        assertEquals("Alpha2", runBlocking { repo.getBySyncId("uid1")?.title })
        assertEquals(6000L, runBlocking { repo.getBySyncId("uid1")?.updatedAt })
        // update via provider
        assertEquals(1, provider.update(notesUri, payload("uid1", "Alpha3", updatedAt = 7000), null, null))
        assertEquals("Alpha3", runBlocking { repo.getBySyncId("uid1")?.title })
        // query all (since=0) returns cursor row with payload + hash
        provider.insert(notesUri, payload("uid2", "Beta", updatedAt = 8000))
        val cursor = provider.query(notesUri, null, null, null, null)!!
        assertEquals(2, cursor.count)
        val titles = mutableSetOf<String>()
        val payloadIdx = cursor.getColumnIndex(RelayNoteProvider.COL_PAYLOAD_JSON)
        val hashIdx = cursor.getColumnIndex(RelayNoteProvider.COL_CONTENT_HASH)
        while (cursor.moveToNext()) {
            val parsed = Json { ignoreUnknownKeys = true }.decodeFromString(RelayNoteJson.serializer(), cursor.getString(payloadIdx))
            titles += parsed.title
            assertEquals(64, cursor.getString(hashIdx).length)
        }
        cursor.close()
        assertEquals(setOf("Alpha3", "Beta"), titles)
        // querySince filters by param, then by selectionArgs
        val sinceCursor = provider.query(Uri.parse("$notesUri/querySince?since=7500"), null, null, null, null)!!
        assertEquals(1, sinceCursor.count); sinceCursor.close()
        val argCursor = provider.query(notesUri, null, null, arrayOf("6500"), null)!!
        assertEquals(2, argCursor.count); argCursor.close()
        val argCursor2 = provider.query(notesUri, null, null, arrayOf("7500"), null)!!
        assertEquals(1, argCursor2.count); argCursor2.close()
        // single delete tombstones
        assertEquals(1, provider.delete(notesUri, "syncId = ?", arrayOf("uid1")))
        assertTrue(runBlocking { repo.getBySyncId("uid1")!!.deleted })
        assertEquals(listOf("uid2"), runBlocking { repo.getAll() }.map { it.syncId })
        // deleted payload via insert also tombstones
        provider.insert(notesUri, payload("uid2", "Beta", updatedAt = 9000, deleted = true))
        assertTrue(runBlocking { repo.getBySyncId("uid2")!!.deleted })
    }

    @Test fun insertRejectsBlankUidBadJsonAndNullValues() {
        assertNull(provider.insert(notesUri, payload("", "NoUid")))
        assertNull(provider.insert(notesUri, ContentValues().apply { put(RelayNoteProvider.COL_PAYLOAD_JSON, "{bad json") }))
        assertNull(provider.insert(notesUri, ContentValues()))
        assertNull(provider.insert(notesUri, null))
        assertEquals(0, provider.update(notesUri, null, null, null))
        assertEquals(0, provider.update(Uri.parse("content://com.vayunmathur.notes.relay/unknown"), payload("x", "X"), null, null))
        assertTrue(runBlocking { repo.getAll() }.isEmpty())
        // unknown uri query is null
        assertNull(provider.query(Uri.parse("content://com.vayunmathur.notes.relay/unknown"), null, null, null, null))
    }

    @Test fun snapshotOpsAndPurge() {
        val snap = ContentValues().apply {
            put(RelayNoteProvider.COL_SYNC_ID, "snap1"); put("kind", "note")
            put(RelayNoteProvider.COL_CONTENT_HASH, "hash1"); put(RelayNoteProvider.COL_UPDATED_AT, 111L); put("remoteModified", 222L)
        }
        assertNotNull(provider.insert(snapsUri, snap))
        assertEquals(1, runBlocking { repo.getAllSnapshots() }.size)
        assertEquals("hash1", runBlocking { repo.getAllSnapshots() }.single().contentHash)
        assertEquals(111L, runBlocking { repo.getAllSnapshots() }.single().localUpdatedAt)
        val cursor = provider.query(snapsUri, null, null, null, null)!!
        assertEquals(1, cursor.count)
        assertTrue(cursor.moveToFirst())
        assertEquals("snap1", cursor.getString(cursor.getColumnIndex(RelayNoteProvider.COL_SYNC_ID)))
        cursor.close()
        assertEquals(1, provider.update(Uri.parse("$snapsUri/upsertRaw"), snap, null, null))
        assertNull(provider.insert(snapsUri, ContentValues())) // no syncId
        // single snapshot delete
        assertEquals(1, provider.delete(snapsUri, "syncId = ?", arrayOf("snap1")))
        assertTrue(runBlocking { repo.getAllSnapshots() }.isEmpty())
        // purge notes also purges snapshots
        provider.insert(notesUri, payload("p1", "PurgeMe"))
        provider.insert(snapsUri, snap)
        assertEquals(0, provider.delete(Uri.parse("$notesUri/deleteByVault"), null, null))
        assertTrue(runBlocking { repo.getAll() }.isEmpty())
        assertTrue(runBlocking { repo.getAllSnapshots() }.isEmpty())
        // selection-less base delete is purge spelling; snapshots purge separately
        provider.insert(notesUri, payload("p2", "Purge2"))
        provider.insert(snapsUri, snap)
        assertEquals(0, provider.delete(snapsUri, null, null)) // deletes all snapshots (selection-less snapshots branch via delete? base snapshots: syncId null -> deleteAll, returns 0)
        // Actually base snapshots delete above consumed snapshots; re-add then purge via deleteByVault
        provider.insert(snapsUri, snap)
        assertEquals(0, provider.delete(Uri.parse("$snapsUri/deleteByVault"), null, null))
        assertTrue(runBlocking { repo.getAllSnapshots() }.isEmpty())
        assertEquals(0, provider.delete(notesUri, null, null))
        assertTrue(runBlocking { repo.getAll() }.isEmpty())
    }

    @Test fun relayJsonRoundTripAndHashStability() {
        val note = Note(id = 9, title = "T", content = "C", position = 2.5, blocks = "{\"b\":1}", syncId = "abc", updatedAt = 42, deleted = true)
        val relay = note.toRelayJson()
        assertEquals(note.copy(), relay.toNote(9))
        assertEquals("abc", relay.uid)
        val defaulted = RelayNoteJson()
        assertEquals("", defaulted.uid)
        assertEquals(Note(id = 3, title = "", content = "", syncId = "", updatedAt = 0).copy(position = 0.0, blocks = null, deleted = false), defaulted.toNote(3))
    }
}
