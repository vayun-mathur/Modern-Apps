package com.vayunmathur.everysync

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.everysync.data.Settings
import com.vayunmathur.everysync.data.VaultMeta
import com.vayunmathur.everysync.data.VaultStore
import com.vayunmathur.everysync.data.readKey
import com.vayunmathur.everysync.network.remote.RelayCursorStore
import com.vayunmathur.everysync.network.remote.RelayLinks
import com.vayunmathur.everysync.network.remote.RelayRoles
import com.vayunmathur.everysync.network.remote.RelaySync
import com.vayunmathur.everysync.provider.SyncState
import com.vayunmathur.everysync.sync.RelayBlobStore
import com.vayunmathur.everysync.sync.RelaySnapshot
import com.vayunmathur.everysync.sync.SyncStatus
import com.vayunmathur.everysync.sync.VaultBaseline
import com.vayunmathur.everysync.sync.VaultKeys
import com.vayunmathur.everysync.sync.OwnerTransfer
import com.vayunmathur.everysync.sync.VaultRoster
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(application = android.app.Application::class)
class StoresAndHelpersTest {
    private val ctx: Context get() = ApplicationProvider.getApplicationContext()

    @Test fun settingsDefaultsAndWrites() = runBlocking {
        suspend fun awaitInterval(v: Long) { repeat(100) { if (Settings.intervalMinutes(ctx) == v) return; kotlinx.coroutines.delay(20) }; assertEquals(v, Settings.intervalMinutes(ctx)) }
        awaitInterval(60L)
        Settings.setIntervalMinutes(ctx, 5); awaitInterval(15L) // coerced to min
        Settings.setIntervalMinutes(ctx, 45); awaitInterval(45L)
        assertEquals(45L, Settings.intervalFlow(ctx).first())
        assertEquals(false, Settings.wifiOnly(ctx)); Settings.setWifiOnly(ctx, true); assertEquals(true, Settings.wifiOnly(ctx))
        assertEquals(true, Settings.wifiOnlyFlow(ctx).first())
        assertEquals(Settings.CONFLICT_LWW, Settings.conflictPolicy(ctx))
        Settings.setConflictPolicy(ctx, Settings.CONFLICT_REMOTE); assertEquals(Settings.CONFLICT_REMOTE, Settings.conflictPolicy(ctx))
        assertEquals("lww", Settings.CONFLICT_LWW)
    }
    @Test fun syncStateAndCursorAndBaseline() = runBlocking {
        assertNull(SyncState.get(ctx, "acc", "scope"))
        SyncState.set(ctx, "acc", "scope", "42"); assertEquals("42", SyncState.get(ctx, "acc", "scope"))
        SyncState.set(ctx, "acc", "scope", null); assertNull(SyncState.get(ctx, "acc", "scope"))
        assertEquals(0, RelayCursorStore.get(ctx, "notes", "v1", "chan"))
        RelayCursorStore.set(ctx, "notes", "v1", "chan", 10); assertEquals(10, RelayCursorStore.get(ctx, "notes", "v1", "chan"))
        assertEquals(false, RelayCursorStore.advance(ctx, "notes", "v1", "chan", 5))
        assertEquals(true, RelayCursorStore.advance(ctx, "notes", "v1", "chan", 20))
        RelayCursorStore.clearVault(ctx, "notes", "v1", listOf("chan")); assertEquals(0, RelayCursorStore.get(ctx, "notes", "v1", "chan"))
        // baseline
        assertTrue(VaultBaseline.load(ctx, "v1", "todo").isEmpty())
        val snap = RelaySnapshot("s1", "todo", "hash", 1, 2, 3)
        VaultBaseline.save(ctx, "v1", "todo", listOf(snap))
        assertEquals(snap, VaultBaseline.load(ctx, "v1", "todo")["s1"])
        VaultBaseline.save(ctx, "v1", "todo", emptyList()) // no-op
        VaultBaseline.remove(ctx, "v1", "todo", listOf("s1")); assertTrue(VaultBaseline.load(ctx, "v1", "todo").isEmpty())
        VaultBaseline.save(ctx, "v1", "todo", listOf(snap)); VaultBaseline.clearVault(ctx, "v1", listOf("todo")); assertTrue(VaultBaseline.load(ctx, "v1", "todo").isEmpty())
    }
    @Test fun vaultStoreSaveAllAndGet() = runBlocking {
        VaultStore.saveAll(ctx, emptyList()); assertTrue(VaultStore.getAll(ctx).isEmpty())
        val m = VaultMeta(vaultId = "v1", dataType = "notes", title = "Notes", keyB64 = "", ownerDeviceId = "dev1")
        VaultStore.saveAll(ctx, listOf(m)); assertEquals(m, VaultStore.get(ctx, "v1")); assertNull(VaultStore.get(ctx, "nope"))
        VaultStore.remove(ctx, "v1"); assertTrue(VaultStore.getAll(ctx).isEmpty())
        // upsert with blank key avoids keystore; getAll corrupt tolerance via saveAll valid only
        VaultStore.upsert(ctx, m); assertEquals(1, VaultStore.getAll(ctx).size)
        VaultStore.migrateKeysToKeystore(ctx) // blank keys: no migration, no crash
        assertNull(m.readKey(ctx)) // blank key cannot unseal
        assertEquals(false, com.vayunmathur.everysync.data.VaultKeyStore.looksSealed("abc"))
        assertEquals(true, com.vayunmathur.everysync.data.VaultKeyStore.looksSealed("v1:abc"))
        assertNull(com.vayunmathur.everysync.data.VaultKeyStore.unseal(ctx, "abc"))
    }
    @Test fun relayRolesAndLinks() {
        assertTrue(RelayRoles.canEdit(RelayRoles.OWNER)); assertTrue(RelayRoles.canEdit(RelayRoles.EDITOR))
        assertEquals(false, RelayRoles.canEdit(RelayRoles.VIEWER)); assertEquals(false, RelayRoles.canEdit("bogus"))
        assertTrue(RelayRoles.canRead(RelayRoles.VIEWER)); assertEquals(false, RelayRoles.canRead(RelayRoles.REVOKED))
        assertEquals(listOf(RelayRoles.EDITOR), RelayRoles.offerable("passwords"))
        assertEquals(listOf(RelayRoles.EDITOR, RelayRoles.VIEWER), RelayRoles.offerable("notes"))
        val link = RelayLinks.shareLinkFor("v1", "dev", "notes"); assertTrue(link.contains("id=v1")); assertTrue(link.contains("owner=dev"))
        assertEquals(Triple("v1", "dev", "notes"), RelayLinks.parseJoinLink("everysync", "join", "v1", "dev", "notes"))
        assertNull(RelayLinks.parseJoinLink("other", "join", "v1", "dev", "notes"))
        assertNull(RelayLinks.parseJoinLink("everysync", "join", "", "dev", "notes"))
        assertNull(RelayLinks.parseJoinLink("everysync", "join", "v1", null, "notes"))
        assertEquals(Triple("v1", "dev", ""), RelayLinks.parseJoinLink("everysync", "join", "v1", "dev", null))
    }
    @Test fun relaySyncPureHelpers() {
        assertEquals("passwords:v1", RelaySync.recordChannel("passwords", "v1"))
        assertTrue(RelaySync.newVaultId().isNotBlank()); assertEquals(32, RelaySync.newVaultKey().size)
        assertNull(RelaySync.parseLive("not json"))
        val msg = RelaySync.parseLive("""{"t":"msg","channel":"c","actions":["a"],"seq":3,"data":"d"}""")
        assertEquals("c", msg?.channel); assertEquals(3, msg?.seq); assertEquals(listOf("a"), msg?.actions)
        assertEquals(false, RelaySync.isLive); RelaySync.stopLive()
        val empty = RelaySync.RecordsResult(emptyList(), 0, rawCount = 0); assertEquals(false, empty.fullyUndecryptable)
        val bad = RelaySync.RecordsResult(emptyList(), 0, rawCount = 5); assertEquals(true, bad.fullyUndecryptable)
        val inv = RelaySync.Invite("v", "k", "t"); assertEquals(RelayRoles.EDITOR, inv.role)
        val jr = RelaySync.JoinRequest(vaultId = "v", requesterId = "r"); assertEquals("req", jr.kind)
    }
    @Test fun signingBytesAreDeterministicAndSorted() {
        val a = VaultKeys.epochSigningBytes("v1", 2, mapOf("b" to "2", "a" to "1"))
        val b = VaultKeys.epochSigningBytes("v1", 2, mapOf("a" to "1", "b" to "2"))
        assertTrue(a.contentEquals(b)); assertTrue(String(a).contains("v1|2|a=1|b=2"))
        assertEquals("keys:v1", VaultKeys.keysChannel("v1")); assertEquals("members:v1", VaultRoster.membersChannel("v1")); assertEquals("owner:v1", OwnerTransfer.ownerChannel("v1"))
        assertEquals("v1|owner|new|key", String(OwnerTransfer.ownerSigningBytes("v1", "new", "key")))
    }
    @Test fun blobStoreLocalFilesAndCodec() {
        assertEquals("notes-blobs:v1", RelayBlobStore.blobChannel("v1"))
        val bytes = "hello".encodeToByteArray()
        assertEquals(RelayBlobStore.sha256(bytes), RelayBlobStore.sha256(bytes)); assertEquals(64, RelayBlobStore.sha256(bytes).length)
        val envJson = RelayBlobStore.encode("a.png", bytes, 100); val env = RelayBlobStore.decode(envJson)
        assertEquals("a.png", env?.name); assertEquals(100L, env?.updatedAt); assertEquals(false, env?.deleted)
        assertEquals(true, RelayBlobStore.decode(RelayBlobStore.encodeDelete("a.png", 5))?.deleted)
        assertNull(RelayBlobStore.decode("junk"))
        RelayBlobStore.writeLocal(ctx, "v1", "a.png", bytes); assertTrue(RelayBlobStore.readLocal(ctx, "v1", "a.png")!!.contentEquals(bytes))
        assertTrue(RelayBlobStore.fileFor(ctx, "v1", "a.png").exists())
        RelayBlobStore.deleteLocal(ctx, "v1", "a.png"); assertNull(RelayBlobStore.readLocal(ctx, "v1", "a.png"))
    }
    @Test fun syncStatusBeginFinish() {
        SyncStatus.begin("acc1"); assertTrue("acc1" in SyncStatus.syncing.value)
        SyncStatus.begin("acc2"); SyncStatus.finish("acc1"); assertEquals(setOf("acc2"), SyncStatus.syncing.value)
        SyncStatus.finish("acc2"); assertTrue(SyncStatus.syncing.value.isEmpty())
    }
    @Test fun vaultNetworkFailuresAreFailClosed() = runBlocking {
        // No RelaySync identity / no network: these must not throw and must return fallbacks.
        val (epoch, key) = VaultKeys.fetchCurrentKey("v1", byteArrayOf(1, 2), null)
        assertEquals(0, epoch); assertTrue(key.contentEquals(byteArrayOf(1, 2)))
        assertTrue(VaultRoster.fetchRoster("v1", ByteArray(0)).isEmpty())
        assertNull(OwnerTransfer.fetchOwnerKey("v1", ""))
        assertNull(OwnerTransfer.fetchOwnerKey("v1", "!!!not-base64!!!"))
    }
}
