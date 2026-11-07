package com.vayunmathur.everysync

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
import com.vayunmathur.everysync.auth.AccountConfig
import com.vayunmathur.everysync.auth.AccountStore
import com.vayunmathur.everysync.auth.TokenStore
import com.vayunmathur.everysync.data.RemoteContact
import com.vayunmathur.everysync.data.RemoteEvent
import com.vayunmathur.everysync.data.RemotePassword
import com.vayunmathur.everysync.data.VaultMeta
import com.vayunmathur.everysync.data.VaultStore
import com.vayunmathur.everysync.data.sink.CalendarSink
import com.vayunmathur.everysync.data.sink.ContactsSink
import com.vayunmathur.everysync.data.sink.NoteSink
import com.vayunmathur.everysync.data.sink.PasswordSink
import com.vayunmathur.everysync.data.sink.TodoIpcSink
import com.vayunmathur.everysync.network.remote.RelaySync
import com.vayunmathur.everysync.provider.DataType
import com.vayunmathur.everysync.provider.SyncDirection
import com.vayunmathur.everysync.provider.SyncState
import com.vayunmathur.everysync.provider.impl.E2eeProvider
import com.vayunmathur.everysync.sync.SnapshotKeyStore
import com.vayunmathur.everysync.sync.SnapshotStore
import com.vayunmathur.everysync.sync.adapters.ContactKind
import javax.crypto.spec.SecretKeySpec
import kotlin.io.encoding.Base64
import kotlinx.coroutines.runBlocking
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowContentResolver
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Contacts/calendar vault cycles against fake system providers, plus the
 * per-type snapshot/restore and purge paths. No network, no AndroidKeyStore.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class SystemVaultDriveTest {
    private val ctx: Context get() = ApplicationProvider.getApplicationContext()
    private val relay get() = RelaySuccessTest.sharedRelay

    class StoreProvider : ContentProvider() {
        val tables = mutableMapOf<String, MutableList<MutableMap<String, Any?>>>()
        var insertCount = 0
        override fun onCreate() = true
        private fun table(uri: Uri) = uri.pathSegments.lastOrNull { it !in setOf("upsertRaw", "querySince", "deleteByVault") } ?: ""
        override fun query(uri: Uri, projection: Array<String>?, sel: String?, args: Array<String>?, sort: String?): Cursor {
            val cols = projection ?: arrayOf("_id")
            val c = MatrixCursor(cols)
            for (row in tables[table(uri)].orEmpty()) c.addRow(cols.map { row[it] }.toTypedArray())
            return c
        }
        override fun insert(uri: Uri, values: ContentValues?): Uri? {
            insertCount++
            val row = mutableMapOf<String, Any?>()
            values?.keySet()?.forEach { row[it] = values.get(it) }
            if (row.isNotEmpty()) tables.getOrPut(table(uri)) { mutableListOf() }.add(row)
            return Uri.withAppendedPath(uri, "99")
        }
        override fun delete(uri: Uri, s: String?, a: Array<String>?): Int {
            // Honor "_id = ?" deletes so sink delete paths remove rows.
            if (s?.contains("_id") == true && !a.isNullOrEmpty()) {
                val rows = tables[table(uri)] ?: return 0
                val before = rows.size
                rows.removeAll { it["_id"]?.toString() == a[0] }
                return before - rows.size
            }
            return 1
        }
        override fun update(uri: Uri, v: ContentValues?, s: String?, a: Array<String>?) = 1
        override fun getType(uri: Uri) = "vnd.test"
    }

    private lateinit var contacts: StoreProvider
    private lateinit var calendars: StoreProvider

    @BeforeTest fun setUp() {
        relay.install()
        FakeIpcProvider.install(NoteSink.AUTHORITY, PasswordSink.AUTHORITY, TodoIpcSink.AUTHORITY)
        contacts = StoreProvider(); ShadowContentResolver.registerProviderInternal(ContactsContract.AUTHORITY, contacts)
        calendars = StoreProvider(); ShadowContentResolver.registerProviderInternal(CalendarContract.AUTHORITY, calendars)
        val softwareKey = SecretKeySpec(ByteArray(32) { (it * 13 + 7).toByte() }, "AES")
        com.vayunmathur.everysync.data.VaultKeyStore.secretKeyForTest = softwareKey
        SnapshotKeyStore.secretKeyForTest = softwareKey
        TokenStore.getInstance(ctx).secretKeyForTest = softwareKey
    }

    private suspend fun makeVault(type: String, vaultId: String, bind: Boolean = true): Pair<VaultMeta, ByteArray> {
        val key = RelaySync.newVaultKey()
        val meta = VaultMeta(vaultId, type, "T-$type", Base64.encode(key), RelaySync.deviceId, Base64.encode(RelaySync.publicBundle), isOwner = true)
        VaultStore.upsert(ctx, meta)
        if (bind) SyncState.set(ctx, ACCOUNT, "e2ee_vault_$type", vaultId)
        return VaultStore.get(ctx, vaultId)!! to key
    }

    private fun seedContact() {
        contacts.tables["raw_contacts"] = mutableListOf(
            mutableMapOf("_id" to 7L, "source_id" to "c1", "sync1" to "100", "deleted" to 0),
        )
        contacts.tables["data"] = mutableListOf(
            mutableMapOf("mimetype" to "vnd.android.cursor.item/name", "data1" to "Ann Lee", "data2" to "Ann", "data3" to "Lee"),
            mutableMapOf("mimetype" to "vnd.android.cursor.item/phone_v2", "data1" to "+123", "data2" to 2),
        )
    }

    @Test fun contactsAndCalendarVaultCycles() { runBlocking {
        assertTrue(RelaySync.init(ctx))
        // CONTACTS: local row pushes to the relay; a remote edit is stamped back locally
        val (cMeta, cKey) = makeVault("contacts", "v-sys-contacts")
        seedContact()
        val provider = E2eeProvider()
        val cConfig = AccountConfig(ACCOUNT, "e2ee", setOf(DataType.CONTACTS))
        provider.sync(ctx, cConfig, SyncDirection.BOTH)
        val cChannel = RelaySync.recordChannel("contacts", cMeta.vaultId)
        assertEquals(1, relay.channel(cChannel).size)
        val cPulled = RelaySync.pullRecords(cChannel, cKey, 0)
        assertEquals("Ann Lee", ContactKind.fromJson(cPulled!!.items.single())?.displayName)
        // Remote newer version of the same contact
        val remoteContact = ContactKind.fromJson(cPulled.items.single())!!.copy(displayName = "Ann Lee-Remote", updatedAt = 9_999)
        RelaySync.appendRecords(cChannel, cKey, listOf(ContactKind.toJson(remoteContact)))
        val insertsBefore = contacts.insertCount
        provider.sync(ctx, cConfig, SyncDirection.BOTH)
        assertTrue(contacts.insertCount > insertsBefore, "stamped write-back should insert/update")

        // CALENDAR: getOrCreate finds calendar 5, local event pushes, cycle is clean after
        val (eMeta, eKey) = makeVault("calendar", "v-sys-calendar")
        calendars.tables["calendars"] = mutableListOf(mutableMapOf("_id" to 5L))
        CalendarSink.upsertEvent(ctx, ACCOUNT, 5L, RemoteEvent(uid = "ev1", calendarId = "5", summary = "Standup", startMillis = 1_000, endMillis = 2_000, updatedAt = 100))
        val eConfig = AccountConfig(ACCOUNT, "e2ee", setOf(DataType.CALENDAR))
        provider.sync(ctx, eConfig, SyncDirection.BOTH)
        val eChannel = RelaySync.recordChannel("calendar", eMeta.vaultId)
        assertTrue(relay.channel(eChannel).isNotEmpty())
        val ePulled = RelaySync.pullRecords(eChannel, eKey, 0)
        assertTrue(ePulled!!.items.any { it.contains("ev1") })

        // MULTI-TYPE sync runs both vaults in one call (abort-free)
        val both = AccountConfig(ACCOUNT, "e2ee", setOf(DataType.CONTACTS, DataType.CALENDAR))
        provider.sync(ctx, both, SyncDirection.BOTH)
        // PULL-only and PUSH-only directions also complete
        provider.sync(ctx, both, SyncDirection.PULL)
        provider.sync(ctx, both, SyncDirection.PUSH)
        Unit
    } }

    @Test fun contactsDeletesPropagateBothWays() { runBlocking {
        assertTrue(RelaySync.init(ctx))
        val (cMeta, cKey) = makeVault("contacts", "v-del-contacts")
        seedContact()
        val provider = E2eeProvider()
        val cConfig = AccountConfig(ACCOUNT, "e2ee", setOf(DataType.CONTACTS))
        val cChannel = RelaySync.recordChannel("contacts", cMeta.vaultId)
        provider.sync(ctx, cConfig, SyncDirection.BOTH)
        assertEquals(1, relay.channel(cChannel).size)
        // Local delete (platform DELETED flag): must tombstone the relay,
        // not resurrect the relay copy locally.
        contacts.tables["raw_contacts"]!![0]["deleted"] = 1
        provider.sync(ctx, cConfig, SyncDirection.BOTH)
        val afterLocalDelete = RelaySync.pullRecords(cChannel, cKey, 0)!!.items
            .mapNotNull { ContactKind.fromJson(it) }
        assertTrue(afterLocalDelete.last { it.uid == "c1" }.deleted, afterLocalDelete.toString())
        assertTrue(ContactsSink.listLocal(ctx, ACCOUNT).none { it.uid == "c1" && !it.deleted })
        // A fresh contact pushed, then a remote tombstone must delete it
        // locally (not be written back as a live contact).
        contacts.tables["raw_contacts"] = mutableListOf(
            mutableMapOf("_id" to 8L, "source_id" to "c2", "sync1" to "100", "deleted" to 0),
        )
        provider.sync(ctx, cConfig, SyncDirection.BOTH)
        assertTrue(ContactsSink.listLocal(ctx, ACCOUNT).any { it.uid == "c2" && !it.deleted })
        RelaySync.appendRecords(
            cChannel, cKey,
            listOf(ContactKind.toJson(
                ContactKind.fromJson(RelaySync.pullRecords(cChannel, cKey, 0)!!.items.last { it.contains("c2") })!!
                    .copy(deleted = true, updatedAt = 9_999),
            )),
        )
        provider.sync(ctx, cConfig, SyncDirection.BOTH)
        assertTrue(
            ContactsSink.listLocal(ctx, ACCOUNT).none { it.uid == "c2" && !it.deleted },
            ContactsSink.listLocal(ctx, ACCOUNT).toString(),
        )
    } }

    @Test fun resolveCreatesVaultWhenUnboundAndNoneExists() { runBlocking {
        assertTrue(RelaySync.init(ctx))
        // A fresh account with no vaults at all: sync creates + adopts one for the type.
        // Use a dedicated account name so the binding lookup starts empty.
        val fresh = AccountConfig("fresh-acc", "e2ee", setOf(DataType.NOTES))
        // Remove any notes vaults other tests in this class may have made: create path
        // only triggers when VaultStore has no notes vault and no binding.
        for (m in VaultStore.getAll(ctx).filter { it.dataType == "notes" }) VaultStore.remove(ctx, m.vaultId)
        E2eeProvider().sync(ctx, fresh, SyncDirection.BOTH)
        val created = VaultStore.getAll(ctx).filter { it.dataType == "notes" }
        assertEquals(1, created.size)
        assertTrue(created.single().isOwner)
        assertEquals(created.single().vaultId, E2eeProvider.vaultIdFor(ctx, "fresh-acc", "notes"))
        // Syncing again reuses the same vault (no duplicate creation)
        E2eeProvider().sync(ctx, fresh, SyncDirection.BOTH)
        assertEquals(1, VaultStore.getAll(ctx).filter { it.dataType == "notes" }.size)
    } }

    @Test fun purgeTypeSnapshotsThenClears() { runBlocking {
        assertTrue(RelaySync.init(ctx))
        val (nMeta, _) = makeVault("notes", "v-purge-notes")
        NoteSink.upsert(ctx, nMeta.vaultId, com.vayunmathur.everysync.data.RemoteNote(uid = "pn1", title = "Bye", updatedAt = 1))
        val (pMeta, _) = makeVault("passwords", "v-purge-pass")
        PasswordSink.upsert(ctx, pMeta.vaultId, RemotePassword(uid = "pp1", name = "X", updatedAt = 1))
        E2eeProvider.purgeType(ctx, ACCOUNT, DataType.NOTES)
        assertTrue(NoteSink.listLocal(ctx, nMeta.vaultId).isEmpty())
        // Purge took a snapshot first: the way back exists
        assertTrue(SnapshotStore.listForVault(ctx, nMeta.vaultId).isNotEmpty())
        E2eeProvider.purgeType(ctx, ACCOUNT, DataType.PASSWORDS)
        assertTrue(PasswordSink.listLocal(ctx, pMeta.vaultId).isEmpty())
        E2eeProvider.purgeType(ctx, ACCOUNT, DataType.TODOS)
        E2eeProvider.purgeType(ctx, ACCOUNT, DataType.CONTACTS)
        E2eeProvider.purgeType(ctx, ACCOUNT, DataType.CALENDAR)
        // Relay type mapping is stable
        assertEquals("contacts", DataType.CONTACTS.let { with(E2eeProvider) { it.relayType() } })
        assertEquals("todos", DataType.TODOS.let { with(E2eeProvider) { it.relayType() } })
        assertNotNull(E2eeProvider.vaultIdFor(ctx, ACCOUNT, "notes"))
    } }

    @Test fun snapshotsForEveryTypeAndRestore() { runBlocking {
        assertTrue(RelaySync.init(ctx))
        val store = AccountStore.getInstance(ctx)
        store.upsert(AccountConfig(ACCOUNT, "e2ee", emptySet()))
        // passwords
        val (pMeta, _) = makeVault("passwords", "v-snap-pass2")
        PasswordSink.upsert(ctx, pMeta.vaultId, RemotePassword(uid = "sp1", name = "Mail", password = "pw", updatedAt = 3))
        assertTrue(SnapshotStore.snapshotVault(ctx, pMeta.vaultId, "passwords"))
        val pSnap = SnapshotStore.listForVault(ctx, pMeta.vaultId).single()
        PasswordSink.delete(ctx, pMeta.vaultId, "sp1")
        assertEquals(1, SnapshotStore.restore(ctx, pSnap))
        assertTrue(PasswordSink.listLocal(ctx, pMeta.vaultId).any { it.uid == "sp1" })
        // todos
        val (tMeta, _) = makeVault("todos", "v-snap-todo2")
        TodoIpcSink.upsert(ctx, tMeta.vaultId, com.vayunmathur.everysync.data.RemoteTodo(uid = "st1", title = "Task", updatedAt = 3))
        assertTrue(SnapshotStore.snapshotVault(ctx, tMeta.vaultId, "todos"))
        val tSnap = SnapshotStore.listForVault(ctx, tMeta.vaultId).single()
        TodoIpcSink.delete(ctx, tMeta.vaultId, "st1")
        assertEquals(1, SnapshotStore.restore(ctx, tSnap))
        // contacts (single account resolves the vault's account)
        val (cMeta, _) = makeVault("contacts", "v-snap-contacts2")
        seedContact()
        assertTrue(SnapshotStore.snapshotVault(ctx, cMeta.vaultId, "contacts"))
        val cSnap = SnapshotStore.listForVault(ctx, cMeta.vaultId).single()
        assertEquals(1, cSnap.recordCount)
        assertNotNull(SnapshotStore.restore(ctx, cSnap))
        // calendar (no events yet: empty snapshot is healthy when a calendar exists)
        val (eMeta, _) = makeVault("calendar", "v-snap-cal2")
        calendars.tables["calendars"] = mutableListOf(mutableMapOf("_id" to 5L))
        calendars.tables["events"] = mutableListOf(
            mutableMapOf("_id" to 11L, "_sync_id" to "sev1", "sync_data1" to "100", "deleted" to 0, "title" to "Meet", "description" to "", "eventLocation" to "", "dtstart" to 1000L, "dtend" to 2000L, "allDay" to 0, "eventTimezone" to "UTC", "rrule" to null),
        )
        assertTrue(SnapshotStore.snapshotVault(ctx, eMeta.vaultId, "calendar"))
        val eSnap = SnapshotStore.listForVault(ctx, eMeta.vaultId).single()
        assertEquals(1, eSnap.recordCount)
        assertNotNull(SnapshotStore.restore(ctx, eSnap))
        // notes with a blob file: blob is captured + restored
        val (nMeta, _) = makeVault("notes", "v-snap-notes2")
        NoteSink.upsert(ctx, nMeta.vaultId, com.vayunmathur.everysync.data.RemoteNote(uid = "snb1", title = "Pic", updatedAt = 3))
        val blobDir = java.io.File(ctx.filesDir, "relay_blobs/${nMeta.vaultId}").apply { mkdirs() }
        java.io.File(blobDir, "img1.png").writeBytes(byteArrayOf(9, 8, 7))
        assertTrue(SnapshotStore.snapshotVault(ctx, nMeta.vaultId, "notes"))
        val nSnap = SnapshotStore.listForVault(ctx, nMeta.vaultId).single()
        assertEquals(1, nSnap.blobCount)
        java.io.File(blobDir, "img1.png").delete()
        assertEquals(1, SnapshotStore.restore(ctx, nSnap))
        assertTrue(java.io.File(ctx.filesDir, "relay_blobs/${nMeta.vaultId}/img1.png").exists())
    } }

    private companion object { const val ACCOUNT = "alice" }
}
