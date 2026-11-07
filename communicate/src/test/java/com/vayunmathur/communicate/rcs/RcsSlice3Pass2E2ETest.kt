package com.vayunmathur.communicate.rcs

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.communicate.data.rcs.RcsDatabase
import com.vayunmathur.communicate.data.rcs.RcsMlsGroup
import com.vayunmathur.communicate.data.rcs.RcsMlsIdentity
import com.vayunmathur.communicate.data.rcs.e2e.RcsE2E
import com.vayunmathur.communicate.data.rcs.e2e.RcsKeyDirectory
import com.vayunmathur.communicate.data.rcs.e2e.RcsPeerKeys
import com.vayunmathur.communicate.data.rcs.e2e.RustMlsCrypto
import com.vayunmathur.communicate.data.rcs.e2e.e2eThreadFor
import com.vayunmathur.communicate.data.rcs.e2e.groupIdFor
import com.vayunmathur.communicate.data.rcs.e2e.hex
import com.vayunmathur.communicate.data.rcs.e2e.isMlsPayload
import com.vayunmathur.communicate.data.rcs.e2e.localE164
import com.vayunmathur.communicate.data.rcs.e2e.parseMembers
import com.vayunmathur.communicate.data.rcs.e2e.pruneMembers
import kotlinx.coroutines.test.runTest
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
 * Slice-3 pass 2: RcsE2E Room-backed verification state, group/member
 * bookkeeping, and (when the host MLS library loads) a full native
 * identity → key-package → group → encrypt/decrypt round trip.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RcsSlice3Pass2E2ETest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private lateinit var testDb: RcsDatabase
    /** The peer's own device database: MLS state is per device, keyed by group id. */
    private lateinit var peerDb: RcsDatabase

    private fun newDb(): RcsDatabase =
        androidx.room3.Room.inMemoryDatabaseBuilder(context, RcsDatabase::class.java)
            .setDriver(androidx.sqlite.driver.bundled.BundledSQLiteDriver())
            .allowMainThreadQueries()
            .build()

    private inline fun <T> onDevice(db: RcsDatabase, block: () -> T): T {
        RcsDatabase.overrideForTest = db
        try {
            return block()
        } finally {
            RcsDatabase.overrideForTest = testDb
        }
    }

    @BeforeTest
    fun setUp() {
        testDb = newDb()
        peerDb = newDb()
        RcsDatabase.overrideForTest = testDb
        RcsPeerKeys.clear()
    }

    @AfterTest
    fun tearDown() {
        RcsDatabase.overrideForTest = null
        testDb.close()
        peerDb.close()
        RcsPeerKeys.clear()
    }

    @Test
    fun verificationLifecycleThroughRoom() = runTest {
        val peer = "+15553003001"
        assertFalse(RcsE2E.isVerified(context, peer))
        // Verifying with no record is a no-op for isVerified.
        RcsE2E.setVerified(context, peer, true)
        assertFalse(RcsE2E.isVerified(context, peer))
        // Record verification against a package sketch.
        val pkg = byteArrayOf(1, 2, 3, 4, 5, 6)
        RcsE2E.setVerifiedWithPackage(context, peer, pkg)
        assertTrue(RcsE2E.isVerified(context, peer))
        // Same package: no identity-change warning.
        assertFalse(RcsE2E.checkPeerIdentityChange(context, peer, pkg))
        assertTrue(RcsE2E.isVerified(context, peer))
        // Different package: warning fires and verification is cleared.
        assertTrue(RcsE2E.checkPeerIdentityChange(context, peer, byteArrayOf(9, 9, 9)))
        assertFalse(RcsE2E.isVerified(context, peer))
        // Unverified peers never warn; empty packages never warn.
        assertFalse(RcsE2E.checkPeerIdentityChange(context, peer, byteArrayOf(8)))
        RcsE2E.setVerifiedWithPackage(context, peer, pkg)
        assertFalse(RcsE2E.checkPeerIdentityChange(context, peer, ByteArray(0)))
        // Toggle off via the plain setter.
        RcsE2E.setVerified(context, peer, false)
        assertFalse(RcsE2E.isVerified(context, peer))
        RcsE2E.setVerified(context, peer, true)
        assertTrue(RcsE2E.isVerified(context, peer))
    }

    @Test
    fun safetyFingerprintsFromCachedPackages() = runTest {
        val peer = "+15553003002"
        assertNull(RcsE2E.safetyFingerprint(peer))
        RcsPeerKeys.store(peer, byteArrayOf(42, 42, 42, 42))
        val fp = RcsE2E.safetyFingerprint(peer)
        assertNotNull(fp)
        // The digest digits are grouped in fours for read-off comparison.
        val groups = fp.split(" ")
        assertTrue(groups.size >= 16 && groups.all { it.all { c -> c.isDigit() } }, fp)
        assertEquals(fp, RcsE2E.safetyFingerprint(peer))
        // Our own side is empty until we publish (publishing needs transport).
        assertNull(RcsE2E.mySafetyFingerprint("+15553003003"))
        assertNull(RcsKeyDirectory.lastPublishedFor("+15553003003"))
    }

    @Test
    fun groupIdResolutionFromRoomRows() = runTest {
        // No group row.
        assertNull(RcsE2E.groupIdFor(context, "conv-pass2"))
        assertNull(with(RcsE2E) { groupIdFor(context, "conv-pass2") })
        // Pending pseudo-group is not a real group id.
        testDb.mlsGroupDao().upsert(
            RcsMlsGroup(groupIdHex = "pending:+1555", conversationId = "conv-pass2", storage = byteArrayOf(1)),
        )
        assertNull(RcsE2E.groupIdFor(context, "conv-pass2"))
        // A real hex id decodes to bytes (separate conversation: the DAO
        // returns one row per conversation).
        testDb.mlsGroupDao().upsert(
            RcsMlsGroup(groupIdHex = "00ff10ab", conversationId = "conv-pass2-real", storage = byteArrayOf(1)),
        )
        assertEquals(
            listOf(0.toByte(), 0xFF.toByte(), 0x10.toByte(), 0xAB.toByte()),
            RcsE2E.groupIdFor(context, "conv-pass2-real")?.toList(),
        )
        assertEquals(
            listOf(0.toByte(), 0xFF.toByte(), 0x10.toByte(), 0xAB.toByte()),
            with(RcsE2E) { groupIdFor(context, "conv-pass2-real") }?.toList(),
        )
    }

    @Test
    fun memberMapTrackingAndLeafLookup() = runTest {
        // parseMembers / pruneMembers are pure CSV helpers.
        assertEquals(emptyMap(), RcsE2E.parseMembers(""))
        assertEquals(mapOf("+1" to 0, "+2" to 3), RcsE2E.parseMembers("+1=0,+2=3"))
        assertEquals(mapOf("+1" to 0), RcsE2E.parseMembers("+1=0,bogus,+2=x,=2"))
        assertEquals("+1=0", RcsE2E.pruneMembers("+1=0,+2=3", setOf(3)))
        assertEquals("", RcsE2E.pruneMembers("+1=0", setOf(0)))

        // trackAddedMembers assigns sequential leaves after the current max.
        assertEquals("+1=1", RcsE2E.trackAddedMembers("", listOf("+1")))
        assertEquals("+1=1,+2=2", RcsE2E.trackAddedMembers("+1=1", listOf("+2", "+2", " ")))
        assertEquals("+9=5,+1=6", RcsE2E.trackAddedMembers("+9=5", listOf("+1")))

        // Leaf lookup reads the tracked map back from Room.
        testDb.mlsGroupDao().upsert(
            RcsMlsGroup(
                groupIdHex = "abcd", conversationId = "conv-pass2",
                storage = byteArrayOf(0), members = "+1555=1,+1666=2",
            ),
        )
        assertEquals(2, RcsE2E.leafIndexFor(context, "conv-pass2", "+1666"))
        assertEquals(1, RcsE2E.leafIndexFor(context, "conv-pass2", " +1555 "))
        assertNull(RcsE2E.leafIndexFor(context, "conv-pass2", "+1777"))
        assertNull(RcsE2E.leafIndexFor(context, "no-such-conv", "+1555"))
    }

    @Test
    fun localE164AndThreadHelpers() {
        // No line-1 number under Robolectric: null, never a throw.
        assertNull(RcsE2E.localE164(context))
        assertNull(with(RcsE2E) { localE164(context) })
        assertEquals("e2e:+1555", e2eThreadFor("+1555"))
        assertEquals("00ff", byteArrayOf(0, 0xFF.toByte()).hex())
        assertEquals("", ByteArray(0).hex())
    }

    @Test
    fun keyPackagePublishFailsClosedWithoutTransport() = runTest {
        assertFalse(RcsKeyDirectory.publishTo(context, "+15553003001", "+15553003002"))
        assertNull(RcsKeyDirectory.lastPublishedFor("+15553003001"))
    }

    @Test
    fun nativeMlsRoundTripWhenLibraryAvailable() = runTest {
        if (!RustMlsCrypto.isAvailable) {
            // Host library absent: every native entry point fails closed.
            assertNull(RcsE2E.identityFor(context, "+15553004001"))
            assertFalse(RcsE2E.isMlsPayload("Content-Type: ${RcsE2E.CT_MLS}\r\n\r\nQUJDRA=="))
            return@runTest
        }
        val me = "+15553004001"
        val peer = "+15553004002"
        // Identities generate once and persist in Room. On hosts where the
        // loaded crate cannot generate (stale/partial native build), every
        // native flow must fail closed instead.
        val identity = RcsE2E.identityFor(context, me)
        if (identity == null) {
            assertNull(RcsE2E.freshKeyPackage(context, me))
            assertNull(RcsE2E.createEncryptedGroup(context, me, "conv-mls-x", listOf(byteArrayOf(1))))
            assertNull(RcsE2E.joinEncryptedGroup(context, "conv-mls-x", byteArrayOf(1, 2, 3)))
            assertNull(RcsE2E.encryptTo(context, me, "conv-mls-x", byteArrayOf(1)))
            assertNull(RcsE2E.decryptFor(context, "conv-mls-x", byteArrayOf(1, 2, 3)))
            assertFalse(RcsE2E.removeMembers(context, me, "conv-mls-x", listOf(1)))
            assertFalse(RcsE2E.setupEncryptedGroup(context, me, "conv-mls-x", mapOf(peer to byteArrayOf(1))))
            assertFalse(RcsE2E.setupGroupWithPeer(context, me, "conv-mls-x", peer, byteArrayOf(1)))
            return@runTest
        }
        assertEquals(identity.toList(), RcsE2E.identityFor(context, me)?.toList())
        assertNull(RcsE2E.identityFor(context, ""))
        val row = testDb.mlsIdentityDao().get(me)
        assertNotNull(row)
        assertEquals(identity.toList(), row.identity.toList())

        // Key packages for both sides, each on its own device.
        val myKp = RcsE2E.freshKeyPackage(context, me)
        assertNotNull(myKp)
        val peerKp = onDevice(peerDb) {
            assertNotNull(RcsE2E.identityFor(context, peer))
            RcsE2E.freshKeyPackage(context, peer)
        }
        assertNotNull(peerKp)

        // Create a group adding the peer; the peer joins from the Welcome on its device. The
        // Welcome is sealed to the peer's key package, whose private keys sit in the peer's
        // 1:1 pseudo-thread storage, not in the conversation it arrives on.
        val created = RcsE2E.createEncryptedGroup(context, me, "conv-mls-1", listOf(peerKp))
        assertNotNull(created)
        val (commit, welcome) = created
        assertTrue(commit.isNotEmpty() && welcome.isNotEmpty())
        val groupId = onDevice(peerDb) { RcsE2E.joinEncryptedGroup(context, me, welcome) }
        assertNotNull(groupId)
        assertEquals(groupId.toList(), RcsE2E.groupIdFor(context, "conv-mls-1")?.toList())
        assertEquals(groupId.toList(), onDevice(peerDb) { RcsE2E.groupIdFor(context, me) }?.toList())

        // Encrypt on one side, decrypt on the joined side.
        val payload = RcsE2E.encryptTo(context, me, "conv-mls-1", "secret hello".toByteArray(Charsets.UTF_8))
        assertNotNull(payload)
        val decrypted = onDevice(peerDb) { RcsE2E.decryptFor(context, me, payload) }
        assertNotNull(decrypted)
        assertEquals("secret hello", decrypted.first.toString(Charsets.UTF_8))
        assertTrue(decrypted.second)

        // MLS payload detection sees the framed payload by content type.
        assertTrue(RcsE2E.isMlsPayload("Content-Type: ${RcsE2E.CT_MLS}\r\n\r\nAAAA"))
        assertTrue(with(RcsE2E) { isMlsPayload("Content-Type: ${RcsE2E.CT_WELCOME}\r\n\r\nAAAA") })

        // Group operations on unknown groups fail closed.
        assertNull(RcsE2E.encryptTo(context, me, "conv-unknown", byteArrayOf(1)))
        assertNull(RcsE2E.decryptFor(context, "conv-unknown", byteArrayOf(1, 2, 3)))
        assertNull(RcsE2E.joinEncryptedGroup(context, "conv-mls-3", byteArrayOf(1, 2, 3)))
        assertTrue(RcsE2E.createEncryptedGroup(context, me, "conv-x", emptyList()) == null)
    }
}
