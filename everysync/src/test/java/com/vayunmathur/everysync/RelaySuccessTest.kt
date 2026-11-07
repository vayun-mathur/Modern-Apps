package com.vayunmathur.everysync

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.everysync.network.remote.RelayRoles
import com.vayunmathur.everysync.network.remote.RelaySync
import com.vayunmathur.everysync.sync.OwnerTransfer
import com.vayunmathur.everysync.sync.RelayBlobStore
import com.vayunmathur.everysync.sync.VaultKeys
import com.vayunmathur.everysync.sync.VaultRoster
import kotlin.io.encoding.Base64
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.encodeToString
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

/**
 * Success-path tests for the relay stack against the in-memory [FakeRelay].
 * No network is touched (CI has internet, so offline-assuming tests fail there).
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class RelaySuccessTest {
    private val ctx: Context get() = ApplicationProvider.getApplicationContext()
    private lateinit var relay: FakeRelay
    private val json = Json { ignoreUnknownKeys = true }

    companion object { val sharedRelay = FakeRelay() }

    @BeforeTest fun setUp() {
        // One relay for the whole class: RelaySync caches `initialized`, so a
        // fresh fake per method would never see the /register call again.
        relay = sharedRelay; relay.install()
    }
    @AfterTest fun tearDown() { relay.uninstall() }

    private suspend fun initSelf(): String {
        assertTrue(RelaySync.init(ctx), "fake relay register should succeed")
        return RelaySync.deviceId
    }

    @Test fun relaySyncRecordAndInboxRoundTrips() { runBlocking {
        val device = initSelf()
        assertTrue(device.isNotBlank())
        // Idempotent init
        assertTrue(RelaySync.init(ctx))
        // getKey: self registered, unknown 404s
        assertNotNull(RelaySync.getKey(device))
        assertNull(RelaySync.getKey("no-such-device"))
        // Record log round trip with encryption
        val key = RelaySync.newVaultKey()
        val channel = RelaySync.recordChannel("notes", "v1")
        assertEquals(2, RelaySync.appendRecords(channel, key, listOf("one", "two")))
        val pulled = RelaySync.pullRecords(channel, key, 0)
        assertEquals(listOf("one", "two"), pulled?.items)
        assertEquals(2, pulled?.rawCount); assertEquals(0, pulled?.undecryptable)
        assertEquals(listOf("two"), RelaySync.pullRecords(channel, key, 1)?.items)
        // Wrong key: everything undecryptable, never an empty log
        val wrong = RelaySync.pullRecords(channel, RelaySync.newVaultKey(), 0)
        assertTrue(wrong!!.fullyUndecryptable); assertEquals(2, wrong.undecryptable)
        // Raw channels
        assertEquals(1, RelaySync.appendRaw("title:v1", listOf("hello")))
        assertEquals(listOf("hello"), RelaySync.pullRaw("title:v1", 0))
        assertNull(RelaySync.pullRaw("title:v1", 5)?.let { if (it.isEmpty()) null else it })
        // Invite sealed to self comes back through the inbox
        assertTrue(RelaySync.sendInvite(device, "v9", "notes", key, "Shared", RelayRoles.VIEWER, "ownerB64", device, epoch = 2))
        val invites = RelaySync.pullInvites(0)
        assertEquals(1, invites.invites.size)
        assertEquals("v9", invites.invites[0].vaultId); assertEquals(2, invites.invites[0].epoch)
        assertEquals(Base64.encode(key), invites.invites[0].key)
        // Invite to an unknown device cannot be sealed
        assertFalse(RelaySync.sendInvite("ghost", "v9", "notes", key, "T", RelayRoles.EDITOR, "", device))
        // Join request sealed to self sorts into requests, not invites
        assertTrue(RelaySync.sendJoinRequest(device, "v7", "todos", device, "Me"))
        val inbox = RelaySync.pullInbox(0)
        assertEquals(1, inbox.requests.size); assertEquals("v7", inbox.requests[0].vaultId)
        assertEquals(1, inbox.invites.size)
        // Foreign garbage in the inbox is skipped, not fatal
        relay.channels["inbox:$device"]!!.add("!!!not-sealed!!!")
        relay.channels["inbox:$device"]!!.add(Base64.encode(byteArrayOf(1, 2, 3)))
        val inbox2 = RelaySync.pullInbox(0)
        assertEquals(1, inbox2.invites.size); assertEquals(1, inbox2.requests.size)
        // Transport failure and HTTP failure are both null/false, never throws
        relay.transportDown = true
        assertNull(RelaySync.pullRaw("title:v1", 0)); assertNull(RelaySync.appendRaw("x", listOf("y")))
        assertFalse(RelaySync.sendJoinRequest(device, "v1", "notes", device, "Me"))
        relay.transportDown = false; relay.httpStatus = 500
        assertNull(RelaySync.pullRecords(channel, key, 0))
        assertNull(RelaySync.getKey(device))
        relay.httpStatus = 200
        assertNotNull(RelaySync.getKey(device))
    } }

    @Test fun rosterKeysAndOwnershipSuccessPaths() { runBlocking {
        val device = initSelf()
        val bundle = RelaySync.publicBundle
        val ownerB64 = Base64.encode(bundle)
        // Roster: record, read back verified, last record wins
        assertTrue(VaultRoster.recordMembers("v1", listOf(RelaySync.Member(device, "Me", RelayRoles.OWNER), RelaySync.Member("peer", "Ann", RelayRoles.VIEWER))))
        var roster = VaultRoster.fetchRoster("v1", bundle)
        assertEquals(RelayRoles.OWNER, roster[device]?.role); assertEquals("Ann", roster["peer"]?.name)
        assertEquals(RelayRoles.OWNER, VaultRoster.myRole("v1", bundle))
        assertTrue(VaultRoster.setRole("v1", "peer", "Ann", RelayRoles.EDITOR))
        roster = VaultRoster.fetchRoster("v1", bundle)
        assertEquals(RelayRoles.EDITOR, roster["peer"]?.role)
        // A forged envelope (signed by nobody the owner trusts is impossible here, so tamper the payload)
        val payload = json.encodeToString(listOf(RelaySync.Member("evil", "Eve", RelayRoles.OWNER)))
        val forged = json.encodeToString(RelaySync.SignedEnvelope("evil", Base64.encode(byteArrayOf(9, 9)), payload))
        relay.channels["members:v1"]!!.add(forged)
        relay.channels["members:v1"]!!.add("not json")
        roster = VaultRoster.fetchRoster("v1", bundle)
        assertFalse(roster.containsKey("evil"))
        // Blank member ids are dropped
        assertTrue(VaultRoster.recordMembers("v1", listOf(RelaySync.Member("", "Nobody", RelayRoles.EDITOR))))
        assertFalse(VaultRoster.fetchRoster("v1", bundle).containsKey(""))

        // Key epochs: rotate seals a fresh key to members; fetch resolves it
        val inviteKey = RelaySync.newVaultKey()
        val newKey = VaultKeys.rotateKey("v1", 0, mapOf(device to bundle, "empty" to ByteArray(0)))
        assertNotNull(newKey); assertFalse(newKey.contentEquals(inviteKey))
        val (epoch, fetched) = VaultKeys.fetchCurrentKey("v1", inviteKey, bundle, inviteEpoch = 0)
        assertEquals(1, epoch); assertTrue(fetched.contentEquals(newKey))
        // A device not wrapped in the epoch keeps the invite key
        val other = VaultKeys.fetchCurrentKey("v1", inviteKey, bundle, inviteEpoch = 0, deviceId = "someone-else")
        assertEquals(0, other.first); assertTrue(other.second.contentEquals(inviteKey))
        // Epoch for a different vault (bad signature for this vault) is ignored
        VaultKeys.rotateKey("v-other", 5, mapOf(device to bundle))
        val still = VaultKeys.fetchCurrentKey("v1", inviteKey, bundle, inviteEpoch = 0)
        assertEquals(1, still.first)
        // Revoke: role recorded, then rotation excludes the revoked member
        val afterRevoke = VaultKeys.revokeAndRotate("v1", "peer", "Ann", RelayRoles.REVOKED, 1, mapOf(device to bundle, "peer" to bundle), bundle)
        assertNotNull(afterRevoke)
        val rosterAfter = VaultRoster.fetchRoster("v1", bundle)
        assertEquals(RelayRoles.REVOKED, rosterAfter["peer"]?.role)
        val (epoch2, key2) = VaultKeys.fetchCurrentKey("v1", inviteKey, bundle, inviteEpoch = 0)
        assertEquals(2, epoch2); assertTrue(key2.contentEquals(afterRevoke))

        // Ownership: transfer to self (registered) follows the signed chain
        assertTrue(OwnerTransfer.transferOwnership("v1", device, "Me2", "Me"))
        val current = OwnerTransfer.fetchOwnerKey("v1", ownerB64)
        assertNotNull(current); assertTrue(current.contentEquals(bundle))
        // Transfer to an unknown device fails at getKey
        assertFalse(OwnerTransfer.transferOwnership("v1", "ghost-device", "Ghost", "Me"))
        // A tampered transfer record does not advance the chain
        relay.channels["owner:v1"]!!.add(Base64.encode("junk".encodeToByteArray()))
        assertTrue(OwnerTransfer.fetchOwnerKey("v1", ownerB64)!!.contentEquals(bundle))
    } }

    @Test fun blobStorePushPullDeleteRoundTrip() { runBlocking {
        initSelf()
        val key = RelaySync.newVaultKey()
        assertEquals("notes-blobs:vB", RelayBlobStore.blobChannel("vB"))
        val seq = RelayBlobStore.push("vB", key, listOf("a.png" to "imgbytes".encodeToByteArray(), "b.png" to byteArrayOf(1, 2)))
        assertNotNull(seq)
        val pulled = RelayBlobStore.pull(ctx, "vB", key)
        assertEquals(setOf("a.png", "b.png"), pulled.map { it.name }.toSet())
        assertEquals("imgbytes", RelayBlobStore.readLocal(ctx, "vB", "a.png")?.toString(Charsets.UTF_8))
        // Delete marker propagates and removes the local copy on pull
        assertNotNull(RelayBlobStore.pushDeletes("vB", key, listOf("a.png")))
        val after = RelayBlobStore.pull(ctx, "vB", key)
        assertTrue(after.any { it.name == "a.png" && it.deleted })
        assertNull(RelayBlobStore.readLocal(ctx, "vB", "a.png"))
        assertNotNull(RelayBlobStore.readLocal(ctx, "vB", "b.png"))
        // Wrong key pulls nothing (fail closed, no throw)
        assertTrue(RelayBlobStore.pull(ctx, "vB", RelaySync.newVaultKey()).isEmpty())
    } }

    @Test fun blobNamesCannotEscapeVaultDir() { runBlocking {
        initSelf()
        val key = RelaySync.newVaultKey()
        // Direct local access with a traversal name is a no-op
        RelayBlobStore.writeLocal(ctx, "vEvil", "../escape.png", byteArrayOf(1))
        assertNull(RelayBlobStore.readLocal(ctx, "vEvil", "../escape.png"))
        assertFalse(java.io.File(ctx.filesDir, "escape.png").exists())
        assertFalse(RelayBlobStore.isSafeBlobName("a/b.png"))
        assertFalse(RelayBlobStore.isSafeBlobName(".."))
        assertTrue(RelayBlobStore.isSafeBlobName("a.png"))
        // A relay envelope carrying a traversal name is skipped on pull:
        // nothing is written outside (or inside) the vault blob dir.
        assertNotNull(RelayBlobStore.push("vEvil", key, listOf("../escape2.png" to byteArrayOf(2))))
        assertTrue(RelayBlobStore.pull(ctx, "vEvil", key).isEmpty())
        assertFalse(java.io.File(ctx.filesDir, "escape2.png").exists())
        assertFalse(java.io.File(ctx.filesDir, "relay_blobs/escape2.png").exists())
    } }
}
