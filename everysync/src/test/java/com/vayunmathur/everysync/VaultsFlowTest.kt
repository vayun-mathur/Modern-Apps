package com.vayunmathur.everysync

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.everysync.auth.TokenStore
import com.vayunmathur.everysync.data.sink.NoteSink
import com.vayunmathur.everysync.data.sink.PasswordSink
import com.vayunmathur.everysync.data.sink.TodoIpcSink
import com.vayunmathur.everysync.network.remote.RelayRoles
import com.vayunmathur.everysync.network.remote.RelaySync
import com.vayunmathur.everysync.platform.EverySyncViewModel
import com.vayunmathur.everysync.sync.SnapshotKeyStore
import javax.crypto.spec.SecretKeySpec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Vault sharing flows (create/invite/join/approve/deny) over the fake relay. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class VaultsFlowTest {
    private val app: Application get() = ApplicationProvider.getApplicationContext()
    private val relay get() = RelaySuccessTest.sharedRelay

    @BeforeTest fun setUp() {
        Dispatchers.setMain(Dispatchers.Unconfined)
        relay.install()
        FakeIpcProvider.install(NoteSink.AUTHORITY, PasswordSink.AUTHORITY, TodoIpcSink.AUTHORITY)
        val softwareKey = SecretKeySpec(ByteArray(32) { (it * 3 + 5).toByte() }, "AES")
        com.vayunmathur.everysync.data.VaultKeyStore.secretKeyForTest = softwareKey
        SnapshotKeyStore.secretKeyForTest = softwareKey
        TokenStore.getInstance(app).secretKeyForTest = softwareKey
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

    @Test fun createInviteJoinApproveAndSafetyCode() {
        val vm = EverySyncViewModel(app)
        vm.vaults.load()
        assertTrue(await { vm.vaults.deviceId.value == RelaySync.deviceId && RelaySync.deviceId.isNotBlank() }, "deviceId=${vm.vaults.deviceId.value}")
        assertTrue(vm.vaults.vaults.value.none { it.title == "My Notes" })

        var createResult: Boolean? = null
        vm.vaults.createVault("notes", "My Notes") { createResult = it }
        assertTrue(await { createResult != null }); assertEquals(true, createResult)
        val meta = vm.vaults.vaults.value.first { it.title == "My Notes" }
        assertTrue(meta.isOwner); assertEquals("notes", meta.dataType)
        // Owner roster + signed title landed on the relay
        assertTrue(relay.channel("members:${meta.vaultId}").isNotEmpty())
        assertTrue(relay.channel("title:${meta.vaultId}").isNotEmpty())
        // Vault UI state is computed with a share link and the owner member
        assertTrue(await { vm.vaults.vaultUi.value.containsKey(meta.vaultId) })
        val ui = vm.vaults.vaultUi.value.getValue(meta.vaultId)
        assertTrue(ui.shareLink.contains(meta.vaultId))
        assertTrue(ui.members.any { it.role == RelayRoles.OWNER })
        assertEquals(ui.shareLink, vm.vaults.shareLinkFor(meta))

        // A later roster record for the same member replaces the earlier
        // one in the UI (last-wins), it does not add a second row.
        vm.vaults.approveRequest(meta.vaultId, RelaySync.deviceId, "Me", RelayRoles.EDITOR)
        assertTrue(await {
            val members = vm.vaults.vaultUi.value[meta.vaultId]?.members.orEmpty()
            members.count { it.id == RelaySync.deviceId } == 1 &&
                members.single { it.id == RelaySync.deviceId }.role == RelayRoles.EDITOR
        }, vm.vaults.vaultUi.value[meta.vaultId]?.members.toString())

        // Invite self (registered) works, unknown device does not
        var inviteResult: Boolean? = null
        vm.vaults.sendInvite(meta.vaultId, RelaySync.deviceId, RelayRoles.VIEWER) { inviteResult = it }
        assertTrue(await { inviteResult != null }); assertEquals(true, inviteResult)
        inviteResult = null
        vm.vaults.sendInvite(meta.vaultId, "ghost-device", RelayRoles.EDITOR) { inviteResult = it }
        assertTrue(await { inviteResult != null }); assertEquals(false, inviteResult)
        // Inviting for a missing vault fails closed
        inviteResult = null
        vm.vaults.sendInvite("no-such-vault", RelaySync.deviceId, RelayRoles.EDITOR) { inviteResult = it }
        assertTrue(await { inviteResult != null }); assertEquals(false, inviteResult)

        // Join request to self, pulled back as a pending request, then approved
        var joinResult: Boolean? = null
        vm.vaults.requestToJoin(meta.vaultId, RelaySync.deviceId, "notes") { joinResult = it }
        assertTrue(await { joinResult != null }); assertEquals(true, joinResult)
        vm.vaults.refreshInbox()
        assertTrue(await {
            vm.vaults.vaultUi.value[meta.vaultId]?.requests?.any { it.requesterId == RelaySync.deviceId } == true
        }, "ui=${vm.vaults.vaultUi.value}")
        vm.vaults.approveRequest(meta.vaultId, RelaySync.deviceId, "Self", RelayRoles.EDITOR)
        assertTrue(await {
            vm.vaults.vaultUi.value[meta.vaultId]?.requests?.none { it.requesterId == RelaySync.deviceId } == true
        })
        // The invite from the approval is in our own inbox channel
        assertTrue(relay.channel("inbox:${RelaySync.deviceId}").isNotEmpty())

        // A second request can be denied instead
        joinResult = null
        vm.vaults.requestToJoin(meta.vaultId, RelaySync.deviceId, "notes") { joinResult = it }
        assertTrue(await { joinResult != null }); assertEquals(true, joinResult)
        vm.vaults.refreshInbox()
        assertTrue(await {
            vm.vaults.vaultUi.value[meta.vaultId]?.requests?.any { it.requesterId == RelaySync.deviceId } == true
        })
        vm.vaults.denyRequest(meta.vaultId, RelaySync.deviceId)
        assertTrue(await {
            vm.vaults.vaultUi.value[meta.vaultId]?.requests?.isEmpty() == true
        })

        // Safety codes: self has one, unknown devices do not
        var code: String? = "unset"; var codeDone = false
        vm.vaults.safetyCode(RelaySync.deviceId) { code = it; codeDone = true }
        assertTrue(await { codeDone }); assertNotNull(code)
        // ViewModel-level safety code + offerable roles (passwords never viewer at VM level either)
        vm.computeSafetyCode(RelaySync.deviceId)
        assertTrue(await { !vm.computingCode.value && vm.safetyCode.value != null })
        assertEquals(listOf(RelayRoles.OWNER, RelayRoles.EDITOR), vm.offerableRoles("passwords"))
        assertEquals(listOf(RelayRoles.OWNER, RelayRoles.EDITOR, RelayRoles.VIEWER), vm.offerableRoles("notes"))
        var ghostCode: String? = "unset"; var ghostDone = false
        vm.vaults.safetyCode("ghost-device") { ghostCode = it; ghostDone = true }
        assertTrue(await { ghostDone }); assertNull(ghostCode)
        assertFalse(vm.vaults.vaultUi.value.isEmpty())
    }
}
