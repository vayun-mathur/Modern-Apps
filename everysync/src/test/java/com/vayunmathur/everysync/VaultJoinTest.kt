package com.vayunmathur.everysync

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.everysync.auth.TokenStore
import com.vayunmathur.everysync.data.VaultStore
import com.vayunmathur.everysync.data.sink.NoteSink
import com.vayunmathur.everysync.data.sink.PasswordSink
import com.vayunmathur.everysync.data.sink.TodoIpcSink
import com.vayunmathur.everysync.network.remote.RelayRoles
import com.vayunmathur.everysync.network.remote.RelaySync
import com.vayunmathur.everysync.platform.EverySyncViewModel
import com.vayunmathur.everysync.sync.SnapshotKeyStore
import javax.crypto.spec.SecretKeySpec
import kotlin.io.encoding.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Joining a shared vault: inbox invite adoption, signed title, verified roster. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class VaultJoinTest {
    private val app: Application get() = ApplicationProvider.getApplicationContext()
    private val relay get() = RelaySuccessTest.sharedRelay
    private val json = Json { ignoreUnknownKeys = true }

    @BeforeTest fun setUp() {
        Dispatchers.setMain(Dispatchers.Unconfined)
        relay.install()
        FakeIpcProvider.install(NoteSink.AUTHORITY, PasswordSink.AUTHORITY, TodoIpcSink.AUTHORITY)
        val softwareKey = SecretKeySpec(ByteArray(32) { (it * 19 + 2).toByte() }, "AES")
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

    @Test fun joinedVaultAdoptedWithSignedTitleAndRoster() { runBlocking {
        assertTrue(RelaySync.init(app))
        val device = RelaySync.deviceId
        val vaultId = "v-joined-1"
        val vaultKey = RelaySync.newVaultKey()
        // The owner (this same identity, acting as the sharing device) publishes
        // a signed title and roster, then seals us the invite.
        val titlePayload = json.encodeToString(mapOf("title" to "Shared Notes"))
        val titleEnv = json.encodeToString(RelaySync.SignedEnvelope(device, Base64.encode(RelaySync.sign(titlePayload.encodeToByteArray())), titlePayload))
        RelaySync.appendRaw("title:$vaultId", listOf(titleEnv))
        // A forged title (bad signature) must be ignored in favour of the signed one
        val forgedPayload = json.encodeToString(mapOf("title" to "Forged"))
        val forgedEnv = json.encodeToString(RelaySync.SignedEnvelope("mallory", Base64.encode(byteArrayOf(1, 1, 1)), forgedPayload))
        RelaySync.appendRaw("title:$vaultId", listOf(forgedEnv))
        assertTrue(RelaySync.sendInvite(device, vaultId, "notes", vaultKey, "Placeholder title", RelayRoles.EDITOR, Base64.encode(RelaySync.publicBundle), device, epoch = 1))

        val vm = EverySyncViewModel(app)
        vm.vaults.refreshInbox()
        assertTrue(await { VaultStore.get(app, vaultId) != null }, "vaults=${VaultStore.getAll(app)}")
        val joined = VaultStore.get(app, vaultId)!!
        assertEquals(false, joined.isOwner)
        assertEquals(1, joined.epoch)
        // The signed title wins over the invite's placeholder once the vault UI refreshes
        assertTrue(await { vm.vaults.vaultUi.value[vaultId]?.meta?.title == "Shared Notes" },
            "ui=${vm.vaults.vaultUi.value[vaultId]}")
        assertTrue(vm.vaults.vaultUi.value.getValue(vaultId).shareLink.contains(vaultId))
        // Inbox cursor advanced: a second refresh does not re-adopt or duplicate
        vm.vaults.refreshInbox()
        assertTrue(await { VaultStore.getAll(app).count { it.vaultId == vaultId } == 1 })
        Unit
    } }
}
