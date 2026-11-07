package com.vayunmathur.everysync

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.e2ee.E2ee
import com.vayunmathur.everysync.network.remote.RelayRoles
import com.vayunmathur.everysync.network.remote.RelaySync
import com.vayunmathur.everysync.sync.OwnerTransfer
import com.vayunmathur.everysync.sync.RelayBlobStore
import com.vayunmathur.everysync.sync.VaultKeys
import com.vayunmathur.everysync.sync.VaultRoster
import kotlin.io.encoding.Base64
import kotlinx.coroutines.runBlocking
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class RelayIdentityTest {
    private val ctx: Context get() = ApplicationProvider.getApplicationContext()

    @Test fun identityCryptoAndNetworkFailPaths() { runBlocking {
        // init attempts register over the (unreachable) relay: false offline, but identity is created.
        val ok = runCatching { RelaySync.init(ctx) }.getOrDefault(false)
        assertEquals(false, ok) // no network in tests
        // After the init attempt the PQC identity exists, so local crypto works.
        val bundle = RelaySync.publicBundle
        assertTrue(bundle.isNotEmpty()); assertTrue(RelaySync.deviceId.isNotBlank())
        val sig = RelaySync.sign("hello".encodeToByteArray())
        assertTrue(RelaySync.verify(bundle, "hello".encodeToByteArray(), sig))
        assertEquals(false, RelaySync.verify(bundle, "tampered".encodeToByteArray(), sig))
        val sealed = RelaySync.seal(bundle, "secret".encodeToByteArray())
        assertEquals("secret", RelaySync.unseal(sealed).decodeToString())
        assertTrue(RelaySync.securityCode(bundle) != null)
        // AES record helpers
        val key = RelaySync.newVaultKey()
        val enc = Base64.encode(E2ee.aesEncrypt(key, "plain".encodeToByteArray()))
        assertEquals("plain", RelaySync.decrypt(key, enc)); assertNull(RelaySync.decrypt(key, "!!!"))
        // Network-backed calls fail closed (null / false / empty), never throw.
        assertNull(RelaySync.getKey("peer")); assertNull(RelaySync.appendRaw("chan", listOf("x")))
        assertNull(RelaySync.pullRaw("chan", 0)); assertNull(RelaySync.appendRecords("chan", key, listOf("x")))
        assertNull(RelaySync.pullRecords("chan", key, 0))
        assertEquals(false, RelaySync.sendInvite("peer", "v1", "notes", key, "T", RelayRoles.EDITOR, "", "dev"))
        assertEquals(false, RelaySync.sendJoinRequest("owner", "v1", "notes", "req", "Name"))
        val inbox = RelaySync.pullInbox(0); assertTrue(inbox.invites.isEmpty() && inbox.requests.isEmpty())
        val invites = RelaySync.pullInvites(0); assertTrue(invites.invites.isEmpty())
        assertEquals(false, RelaySync.liveAppend("chan", key, listOf("x")))
    } }
    @Test fun vaultKeysRosterAndTransferFailClosed() { runBlocking {
        runCatching { RelaySync.init(ctx) }
        val bundle = RelaySync.publicBundle
        val inviteKey = byteArrayOf(9, 9, 9)
        // fetchCurrentKey: pull fails -> invite key kept
        val (epoch, key) = VaultKeys.fetchCurrentKey("v1", inviteKey, bundle, inviteEpoch = 3)
        assertEquals(3, epoch); assertTrue(key.contentEquals(inviteKey))
        // rotateKey: wraps + signs locally, append fails -> null
        assertNull(VaultKeys.rotateKey("v1", 0, mapOf("peer" to bundle, "empty" to ByteArray(0))))
        assertNull(VaultKeys.rotateKey("v1", 0, emptyMap()))
        // revoke: setRole append fails -> null
        assertNull(VaultKeys.revokeAndRotate("v1", "peer", "P", RelayRoles.REVOKED, 0, mapOf("peer" to bundle), bundle))
        // roster
        assertEquals(false, VaultRoster.recordMembers("v1", listOf(RelaySync.Member("m1", "Ann", RelayRoles.EDITOR))))
        assertEquals(false, VaultRoster.setRole("v1", "m1", "Ann", RelayRoles.VIEWER))
        assertTrue(VaultRoster.fetchRoster("v1", bundle).isEmpty()); assertNull(VaultRoster.myRole("v1", bundle))
        // owner transfer chain: base key kept when pull fails
        val base = VaultKeys.fetchCurrentKey("v1", inviteKey, null)
        assertEquals(0, base.first)
        val owner = OwnerTransfer.fetchOwnerKey("v1", Base64.encode(bundle))
        assertTrue(owner!!.contentEquals(bundle))
        assertEquals(false, OwnerTransfer.transferOwnership("v1", "newowner", "New", "Self"))
        // blob pull/push fail closed
        assertTrue(RelayBlobStore.pull(ctx, "v1", ByteArray(32){2}).isEmpty())
        assertNull(RelayBlobStore.push("v1", inviteKey, emptyList())); assertNull(RelayBlobStore.pushDeletes("v1", inviteKey, emptyList()))
        assertNull(RelayBlobStore.push("v1", ByteArray(32){1}, listOf("a.png" to byteArrayOf(1))))
    } }
}
