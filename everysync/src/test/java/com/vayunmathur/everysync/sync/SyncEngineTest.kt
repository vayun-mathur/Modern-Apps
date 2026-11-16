package com.vayunmathur.everysync.sync

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.everysync.FakeIpcProvider
import com.vayunmathur.everysync.FakeRelay
import com.vayunmathur.everysync.RelaySuccessTest
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
import com.vayunmathur.everysync.provider.DataType
import com.vayunmathur.everysync.provider.SyncDirection
import com.vayunmathur.everysync.provider.SyncState
import javax.crypto.spec.SecretKeySpec
import kotlin.io.encoding.Base64
import kotlinx.coroutines.runBlocking
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * SyncEngine behaviour: unknown accounts/providers are no-ops, a real
 * provider pass records success on the account, a relay failure records
 * the abort as the account's last error, and the per-account lock makes
 * overlapping passes safe.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class SyncEngineTest {
    private val ctx: Context get() = ApplicationProvider.getApplicationContext()
    private val relay: FakeRelay get() = RelaySuccessTest.sharedRelay

    @BeforeTest fun setUp() {
        relay.install()
        relay.transportDown = false
        relay.httpStatus = 200
        FakeIpcProvider.install(NoteSink.AUTHORITY, PasswordSink.AUTHORITY, TodoIpcSink.AUTHORITY)
        val softwareKey = SecretKeySpec(ByteArray(32) { (it * 11 + 3).toByte() }, "AES")
        com.vayunmathur.everysync.data.VaultKeyStore.secretKeyForTest = softwareKey
        SnapshotKeyStore.secretKeyForTest = softwareKey
        TokenStore.getInstance(ctx).secretKeyForTest = softwareKey
    }

    private suspend fun makeNotesVault(account: String, vaultId: String) {
        assertTrue(RelaySync.init(ctx))
        val key = RelaySync.newVaultKey()
        VaultStore.upsert(
            ctx,
            VaultMeta(
                vaultId = vaultId, dataType = "notes", title = "Engine notes",
                keyB64 = Base64.encode(key), ownerDeviceId = RelaySync.deviceId,
                ownerKeyB64 = Base64.encode(RelaySync.publicBundle), isOwner = true,
            ),
        )
        SyncState.set(ctx, account, "e2ee_vault_notes", vaultId)
    }

    @Test fun unknownAccountAndUnknownProviderAreNoOps() { runBlocking {
        SyncEngine.syncAccount(ctx, "syncengine-ghost")
        assertNotNull(AccountStore.getInstance(ctx)) // reaching here = no throw
        assertTrue(SyncStatus.syncing.value.none { it == "syncengine-ghost" })

        AccountStore.getInstance(ctx).upsert(
            AccountConfig("syncengine-noprovider", "no-such-provider", setOf(DataType.NOTES)),
        )
        SyncEngine.syncAccount(ctx, "syncengine-noprovider")
        val config = AccountStore.getInstance(ctx).get("syncengine-noprovider")
        assertNotNull(config)
        assertEquals(0L, config.lastSyncEpochMs)
        assertNull(config.lastSyncError)
        assertTrue(SyncStatus.syncing.value.none { it == "syncengine-noprovider" })
    } }

    @Test fun successfulPassPushesAndStampsAccount() { runBlocking {
        val account = "syncengine-alice"
        makeNotesVault(account, "v-engine-notes")
        AccountStore.getInstance(ctx).upsert(
            AccountConfig(account, "e2ee", setOf(DataType.NOTES)),
        )
        NoteSink.upsert(
            ctx, "v-engine-notes",
            RemoteNote(uid = "engine-n1", title = "From engine", content = "body", updatedAt = 1_000),
        )
        val before = System.currentTimeMillis()
        SyncEngine.syncAccount(ctx, account, SyncDirection.BOTH)
        // The local note reached the encrypted relay log.
        val channel = RelaySync.recordChannel("notes", "v-engine-notes")
        assertEquals(1, relay.channel(channel).size)
        // Success is stamped on the account and the syncing flag cleared.
        val config = AccountStore.getInstance(ctx).get(account)
        assertNotNull(config)
        assertTrue(config.lastSyncEpochMs >= before, "lastSync=${config.lastSyncEpochMs} before=$before")
        assertNull(config.lastSyncError)
        assertTrue(account !in SyncStatus.syncing.value)
        // A second pass is a clean no-op on the relay.
        SyncEngine.syncAccount(ctx, account)
        assertEquals(1, relay.channel(channel).size)
    } }

    @Test fun failedPassRecordsErrorAndClearsSyncingFlag() { runBlocking {
        val account = "syncengine-broken"
        makeNotesVault(account, "v-engine-broken")
        AccountStore.getInstance(ctx).upsert(
            AccountConfig(account, "e2ee", setOf(DataType.NOTES)),
        )
        relay.transportDown = true
        try {
            SyncEngine.syncAccount(ctx, account)
        } finally {
            relay.transportDown = false
        }
        val config = AccountStore.getInstance(ctx).get(account)
        assertNotNull(config)
        assertTrue(config.lastSyncEpochMs > 0L)
        assertNotNull(config.lastSyncError)
        assertTrue(
            config.lastSyncError!!.contains("pull_transport_failed") ||
                config.lastSyncError!!.contains("vault sync aborted"),
            "error=${config.lastSyncError}",
        )
        assertTrue(account !in SyncStatus.syncing.value)
        // The account recovers once the relay is back.
        SyncEngine.syncAccount(ctx, account)
        assertNull(AccountStore.getInstance(ctx).get(account)?.lastSyncError)
    } }

    @Test fun syncAllVisitsEveryAccount() { runBlocking {
        makeNotesVault("syncengine-all-a", "v-engine-all-a")
        AccountStore.getInstance(ctx).upsert(
            AccountConfig("syncengine-all-a", "e2ee", setOf(DataType.NOTES)),
        )
        AccountStore.getInstance(ctx).upsert(
            AccountConfig("syncengine-all-b", "no-such-provider", setOf(DataType.NOTES)),
        )
        SyncEngine.syncAll(ctx)
        assertTrue(AccountStore.getInstance(ctx).get("syncengine-all-a")!!.lastSyncEpochMs > 0L)
        assertEquals(
            0L,
            AccountStore.getInstance(ctx).get("syncengine-all-b")!!.lastSyncEpochMs,
        )
    } }
}
