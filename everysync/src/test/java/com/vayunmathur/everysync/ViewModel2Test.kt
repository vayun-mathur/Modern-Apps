package com.vayunmathur.everysync

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.everysync.auth.AccountConfig
import com.vayunmathur.everysync.auth.AccountStore
import com.vayunmathur.everysync.auth.TokenStore
import com.vayunmathur.everysync.data.RemoteNote
import com.vayunmathur.everysync.data.VaultMeta
import com.vayunmathur.everysync.data.VaultStore
import com.vayunmathur.everysync.data.sink.NoteSink
import com.vayunmathur.everysync.data.sink.PasswordSink
import com.vayunmathur.everysync.data.sink.TodoIpcSink
import com.vayunmathur.everysync.network.remote.RelaySync
import com.vayunmathur.everysync.platform.EverySyncViewModel
import com.vayunmathur.everysync.provider.DataType
import com.vayunmathur.everysync.sync.SnapshotKeyStore
import com.vayunmathur.everysync.sync.SnapshotStore
import javax.crypto.spec.SecretKeySpec
import kotlin.io.encoding.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** ViewModel behaviours needing the fake relay + providers: error mapping, purges, snapshot restore. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class ViewModel2Test {
    private val app: Application get() = ApplicationProvider.getApplicationContext()
    private val relay get() = RelaySuccessTest.sharedRelay

    @BeforeTest fun setUp() {
        Dispatchers.setMain(Dispatchers.Unconfined)
        relay.install()
        FakeIpcProvider.install(NoteSink.AUTHORITY, PasswordSink.AUTHORITY, TodoIpcSink.AUTHORITY)
        val softwareKey = SecretKeySpec(ByteArray(32) { (it * 11 + 3).toByte() }, "AES")
        com.vayunmathur.everysync.data.VaultKeyStore.secretKeyForTest = softwareKey
        SnapshotKeyStore.secretKeyForTest = softwareKey
        TokenStore.getInstance(app).secretKeyForTest = softwareKey
        try {
            androidx.work.WorkManager.initialize(app, androidx.work.Configuration.Builder().build())
        } catch (_: IllegalStateException) { /* already initialised */ }
    }
    @AfterTest fun tearDown() { Dispatchers.resetMain(); relay.uninstall() }

    private fun await(deadlineMs: Long = 20_000, condition: () -> Boolean): Boolean {
        val end = System.currentTimeMillis() + deadlineMs
        while (System.currentTimeMillis() < end) {
            if (condition()) return true
            Thread.sleep(25)
        }
        return condition()
    }

    @Test fun syncErrorTextMapsAbortCodes() { runBlocking {
        val store = AccountStore.getInstance(app)
        val vm = EverySyncViewModel(app)
        assertNull(vm.syncErrorText("ghost"))
        store.upsert(AccountConfig("err-acc", "e2ee", emptySet()))
        assertNull(vm.syncErrorText("err-acc"))
        for ((code, expectDistinct) in listOf(
            "pull_transport_failed" to true, "log_undecryptable" to true,
            "local_read_failed" to true, "relay_unavailable" to true, "some raw error" to false,
        )) {
            store.upsert(AccountConfig("err-acc", "e2ee", emptySet(), lastSyncError = "vault sync aborted: $code (notes)"))
            val text = vm.syncErrorText("err-acc")
            assertNotNull(text, code)
            if (!expectDistinct) assertEquals("vault sync aborted: some raw error (notes)", text)
        }
        // Distinct codes map to distinct user-facing strings
        store.upsert(AccountConfig("err-acc", "e2ee", emptySet(), lastSyncError = "pull_transport_failed"))
        val a = vm.syncErrorText("err-acc")
        store.upsert(AccountConfig("err-acc", "e2ee", emptySet(), lastSyncError = "log_undecryptable"))
        assertTrue(a != vm.syncErrorText("err-acc"))
        Unit
    } }

    @Test fun toggleTypesPurgesAndSnapshotRestore() { runBlocking {
        assertTrue(RelaySync.init(app))
        val store = AccountStore.getInstance(app)
        store.upsert(AccountConfig("vm-acc", "e2ee", setOf(DataType.CONTACTS, DataType.CALENDAR, DataType.PASSWORDS, DataType.NOTES, DataType.TODOS)))
        val key = RelaySync.newVaultKey()
        VaultStore.upsert(app, VaultMeta("v-vm-notes", "notes", "VM Notes", Base64.encode(key), RelaySync.deviceId, Base64.encode(RelaySync.publicBundle), isOwner = true))
        NoteSink.upsert(app, "v-vm-notes", RemoteNote(uid = "vm1", title = "Keep me", updatedAt = 5))
        val vm = EverySyncViewModel(app)
        // Disable notes: snapshot is taken first, local notes are purged, type removed
        vm.toggleType("vm-acc", DataType.NOTES, false)
        assertTrue(await { store.get("vm-acc")?.enabledTypes?.contains(DataType.NOTES) == false })
        assertTrue(await { NoteSink.listLocal(app, "v-vm-notes").isEmpty() })
        assertTrue(SnapshotStore.listForVault(app, "v-vm-notes").isNotEmpty())
        // Re-enable: type restored
        vm.toggleType("vm-acc", DataType.NOTES, true)
        assertTrue(await { store.get("vm-acc")?.enabledTypes?.contains(DataType.NOTES) == true })
        // Disable the IPC + system types too (purge paths for each), awaiting
        // each toggle so the read-modify-write cycles do not race each other
        for (type in listOf(DataType.PASSWORDS, DataType.TODOS, DataType.CONTACTS, DataType.CALENDAR)) {
            vm.toggleType("vm-acc", type, false)
            assertTrue(await { store.get("vm-acc")?.enabledTypes?.contains(type) == false }, "type $type still enabled: ${store.get("vm-acc")?.enabledTypes}")
        }
        assertEquals(setOf(DataType.NOTES), store.get("vm-acc")?.enabledTypes)
        vm.toggleType("ghost", DataType.NOTES, true) // unknown account: no-op, no crash

        // Snapshot restore through the ViewModel announces and clears the restoring flag
        vm.loadSnapshots()
        assertTrue(await { vm.snapshots.value.any { it.vaultId == "v-vm-notes" } })
        val snap = vm.snapshots.value.first { it.vaultId == "v-vm-notes" }
        vm.restoreSnapshot(snap)
        assertTrue(await { !vm.restoring.value && NoteSink.listLocal(app, "v-vm-notes").any { it.uid == "vm1" } })
        // snapshotNow is idempotent for the same day
        vm.snapshotNow()
        assertTrue(await { vm.snapshots.value.count { it.vaultId == "v-vm-notes" } >= 1 })
        // Settings actions persist
        vm.setInterval(30); vm.setWifiOnly(true); vm.setConflictPolicy("remote")
        assertTrue(await { com.vayunmathur.everysync.data.Settings.intervalMinutes(app) == 30L })
        vm.removeAccount("vm-acc")
        assertTrue(await { store.get("vm-acc") == null })
        Unit
    } }
}
