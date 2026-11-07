package com.vayunmathur.everysync

import android.app.Application
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.everysync.auth.AccountConfig
import com.vayunmathur.everysync.auth.AccountStore
import com.vayunmathur.everysync.provider.DataType
import com.vayunmathur.everysync.sync.SnapshotStore
import kotlinx.coroutines.runBlocking
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import kotlin.test.Test
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class ViewModelTest {
    private val app: Application get() = ApplicationProvider.getApplicationContext()
    @org.junit.Before fun initWorkManager() { try { androidx.work.WorkManager.initialize(app, androidx.work.Configuration.Builder().build()) } catch (_: IllegalStateException) {} }
    private fun idle() { shadowOf(Looper.getMainLooper()).idle() }

    @Test fun viewModelActions() { runBlocking {
        val store = AccountStore.getInstance(app)
        store.upsert(AccountConfig("carol", "e2ee", setOf(DataType.CONTACTS)))
        val vm = com.vayunmathur.everysync.platform.EverySyncViewModel(app)
        vm.toggleType("carol", DataType.NOTES, true); idle()
        vm.toggleType("carol", DataType.CONTACTS, false); idle()
        vm.toggleType("ghost", DataType.NOTES, true); idle()
        vm.setInterval(90); idle(); vm.setWifiOnly(true); idle(); vm.setConflictPolicy("remote"); idle()
        runCatching { vm.syncNow("carol") }
        vm.snapshotNow(); idle()
        vm.loadSnapshots(); idle(); assertTrue(vm.snapshots.value.isEmpty())
        vm.restoreSnapshot(SnapshotStore.SnapshotMeta("v1", "notes", 1700000000000, 0)); idle()
        var done = false; vm.addRelayAccount("unknown-provider") { done = true }; idle()
        vm.addRelayAccount("e2ee") { done = true }; idle()
        vm.vaults.load(); idle()
        vm.vaults.createVault("notes", "My Vault") { }; idle()
        vm.vaults.sendInvite("nope", "peer", "editor") { }; idle()
        vm.vaults.approveRequest("nope", "peer"); vm.vaults.requestToJoin("v1", "owner", "notes") { }; idle()
        vm.vaults.refreshInbox(); idle(); vm.vaults.safetyCode("peer") { }; idle()
        vm.announce("hello")
        vm.removeAccount("carol"); idle()
    } }
}
