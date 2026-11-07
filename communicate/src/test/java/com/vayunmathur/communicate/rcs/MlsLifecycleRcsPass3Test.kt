package com.vayunmathur.communicate.rcs

import android.content.Context
import androidx.room3.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.communicate.data.rcs.RcsDatabase
import com.vayunmathur.communicate.data.rcs.RcsSipTransport
import com.vayunmathur.communicate.data.rcs.e2e.RcsE2E
import com.vayunmathur.communicate.data.rcs.e2e.RcsKeyDirectory
import com.vayunmathur.communicate.data.rcs.e2e.RcsPeerKeys
import com.vayunmathur.communicate.data.rcs.e2e.RustMlsCrypto
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
 * Pass 3: the native MLS lifecycle directly and as a three-device group —
 * identity/key-package/group/join/encrypt/decrypt/remove — plus the
 * transport-mediated setup helpers through the fake-sender seam.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MlsLifecycleRcsPass3Test {

    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private lateinit var deviceA: RcsDatabase
    private lateinit var deviceB: RcsDatabase
    private lateinit var deviceC: RcsDatabase

    private fun newDb(): RcsDatabase =
        Room.inMemoryDatabaseBuilder(context, RcsDatabase::class.java)
            .setDriver(BundledSQLiteDriver())
            .allowMainThreadQueries()
            .build()

    private suspend fun <T> onDevice(db: RcsDatabase, block: suspend () -> T): T {
        RcsDatabase.overrideForTest = db
        try {
            return block()
        } finally {
            RcsDatabase.overrideForTest = deviceA
        }
    }

    private val sent = mutableListOf<Triple<String, String, ByteArray>>()

    @BeforeTest fun setUp() {
        deviceA = newDb(); deviceB = newDb(); deviceC = newDb()
        RcsDatabase.overrideForTest = deviceA
        RcsPeerKeys.clear()
        sent.clear()
    }

    @AfterTest fun tearDown() {
        RcsSipTransport.fakeSenderForTest = null
        RcsDatabase.overrideForTest = null
        deviceA.close(); deviceB.close(); deviceC.close()
        RcsPeerKeys.clear()
    }

    @Test fun directNativeLifecycleAndKindProbes() {
        if (!RustMlsCrypto.isAvailable) {
            // Fail-closed probes without the library.
            assertEquals(2, runCatching { RustMlsCrypto.payloadKind(byteArrayOf(1)) }.getOrElse { 2 })
            return
        }
        val aId = RustMlsCrypto.generateIdentity("+15550002001".toByteArray())?.getOrNull(1)
        val bId = RustMlsCrypto.generateIdentity("+15550002002".toByteArray())?.getOrNull(1)
        assertNotNull(aId); assertNotNull(bId)

        val bPkg = RustMlsCrypto.buildKeyPackage(ByteArray(0), bId)
        assertNotNull(bPkg)
        val (bStorage, kpB) = bPkg[0] to bPkg[1]

        val created = RustMlsCrypto.createGroup(ByteArray(0), aId)
        assertNotNull(created)
        var aStorage = created[0]
        val groupId = created[1]
        assertTrue(groupId.isNotEmpty())

        val added = RustMlsCrypto.addMembers(aStorage, aId, groupId, arrayOf(kpB))
        assertNotNull(added)
        aStorage = added[0]
        val commit = added[1]
        val welcome = added[2]

        // Framing probes: welcome kind 0, ciphertext kind 1, junk kind 2.
        assertEquals(0, RustMlsCrypto.payloadKind(welcome))
        assertEquals(2, RustMlsCrypto.payloadKind(byteArrayOf(0, 1, 2, 3)))
        val welcomeMagic = RustMlsCrypto.welcomeMagic()
        assertNotNull(welcomeMagic)
        assertTrue(welcome.copyOf(welcomeMagic.size).contentEquals(welcomeMagic))

        val joined = RustMlsCrypto.joinGroup(bStorage, welcome)
        assertNotNull(joined)
        var bStorageNow = joined[0]
        assertEquals(groupId.toList(), joined[1].toList())

        val encrypted = RustMlsCrypto.encrypt(aStorage, aId, groupId, "direct hello".toByteArray())
        assertNotNull(encrypted)
        aStorage = encrypted[0]
        val payload = encrypted[1]
        assertEquals(1, RustMlsCrypto.payloadKind(payload))
        val messageMagic = RustMlsCrypto.messageMagic()
        assertNotNull(messageMagic)
        assertTrue(payload.copyOf(messageMagic.size).contentEquals(messageMagic))

        val decrypted = RustMlsCrypto.decrypt(bStorageNow, groupId, payload)
        assertNotNull(decrypted)
        bStorageNow = decrypted[0]
        assertEquals(1.toByte(), decrypted[1].firstOrNull())
        assertEquals("direct hello", decrypted[2].toString(Charsets.UTF_8))

        // Removing leaf 1 (B) yields a commit; afterwards B cannot read new
        // ciphertext, whether or not its crate accepts merging the removal
        // commit (a removed member is outside the new epoch either way).
        val removed = RustMlsCrypto.removeMembers(aStorage, aId, groupId, "1".toByteArray(Charsets.US_ASCII))
        assertNotNull(removed)
        aStorage = removed[0]
        // The raw commit lacks the message framing, so the crate may throw
        // here rather than return null; RcsE2E.decryptFor runCatches this.
        val merged = runCatching { RustMlsCrypto.decrypt(bStorageNow, groupId, removed[1]) }.getOrNull()
        if (merged != null) {
            assertEquals(0.toByte(), merged[1].firstOrNull())
            bStorageNow = merged[0]
        }
        val afterRemoval = RustMlsCrypto.encrypt(aStorage, aId, groupId, "after removal".toByteArray())
        assertNotNull(afterRemoval)
        assertNull(runCatching { RustMlsCrypto.decrypt(bStorageNow, groupId, afterRemoval[1]) }.getOrNull())

        // Garbage inputs fail closed (null, or a native error the Kotlin
        // layer converts to null).
        assertNull(runCatching { RustMlsCrypto.joinGroup(ByteArray(0), byteArrayOf(1, 2, 3)) }.getOrNull())
        assertNull(runCatching { RustMlsCrypto.decrypt(bStorageNow, groupId, byteArrayOf(4, 5, 6)) }.getOrNull())
        assertNull(runCatching { RustMlsCrypto.encrypt(ByteArray(0), aId, groupId, byteArrayOf(1)) }.getOrNull())
    }

    @Test fun threeDeviceGroupCreateJoinDecryptAndRemove() = runTest {
        if (!RustMlsCrypto.isAvailable) return@runTest
        val meA = "+15550002001"
        val meB = "+15550002002"
        val meC = "+15550002003"
        val groupConv = "group-pass3"

        val kpB = onDevice(deviceB) {
            RcsE2E.identityFor(context, meB)
            RcsE2E.freshKeyPackage(context, meB)
        }
        val kpC = onDevice(deviceC) {
            RcsE2E.identityFor(context, meC)
            RcsE2E.freshKeyPackage(context, meC)
        }
        assertNotNull(kpB); assertNotNull(kpC)

        val created = RcsE2E.createEncryptedGroup(context, meA, groupConv, listOf(kpB, kpC))
        assertNotNull(created)
        val (_, welcome) = created

        val gidB = onDevice(deviceB) { RcsE2E.joinEncryptedGroup(context, groupConv, welcome) }
        val gidC = onDevice(deviceC) { RcsE2E.joinEncryptedGroup(context, groupConv, welcome) }
        assertNotNull(gidB); assertNotNull(gidC)
        assertEquals(gidB.toList(), gidC.toList())

        // A broadcasts; both joiners read it.
        val payload = RcsE2E.encryptTo(context, meA, groupConv, "hi all".toByteArray())
        assertNotNull(payload)
        val readB = onDevice(deviceB) { RcsE2E.decryptFor(context, groupConv, payload) }
        val readC = onDevice(deviceC) { RcsE2E.decryptFor(context, groupConv, payload) }
        assertEquals("hi all", readB?.first?.toString(Charsets.UTF_8))
        assertEquals("hi all", readC?.first?.toString(Charsets.UTF_8))

        // Removal through the E.164 map on a second tracked group: no
        // transport, so the commit cannot be delivered and the call reports
        // false, but the member map is still pruned locally. Fresh key
        // packages: the ones above were consumed by the first group.
        val kpB2 = onDevice(deviceB) { RcsE2E.freshKeyPackage(context, meB) }
        val kpC2 = onDevice(deviceC) { RcsE2E.freshKeyPackage(context, meC) }
        assertNotNull(kpB2); assertNotNull(kpC2)
        val trackedConv = "group-pass3-tracked"
        RcsE2E.setupEncryptedGroup(context, meA, trackedConv, mapOf(meB to kpB2, meC to kpC2))
        assertEquals(1, RcsE2E.leafIndexFor(context, trackedConv, meB) ?: -1)
        assertNotNull(RcsE2E.leafIndexFor(context, trackedConv, meC))
        val removed = RcsE2E.removeMembersByE164(context, meA, trackedConv, listOf(meC, "+15559999999"))
        assertFalse(removed) // no transport seam installed in this test
        assertNull(RcsE2E.leafIndexFor(context, trackedConv, meC))
        assertNotNull(RcsE2E.leafIndexFor(context, trackedConv, meB))
        // Removing nobody / unknown groups fails closed.
        assertFalse(RcsE2E.removeMembersByE164(context, meA, trackedConv, emptyList()))
        assertFalse(RcsE2E.removeMembers(context, meA, "no-group", listOf(1)))
        assertFalse(RcsE2E.removeMembers(context, meA, trackedConv, emptyList()))
    }

    @Test fun transportMediatedSetupPublishesAndWelcomes() = runTest {
        if (!RustMlsCrypto.isAvailable) return@runTest
        RcsSipTransport.fakeSenderForTest = { startLine, headers, body ->
            sent += Triple(startLine, headers, body.copyOf())
            true
        }
        val meA = "+15550002001"
        val meB = "+15550002002"

        // Publishing our key package reaches the fake transport and is cached.
        assertTrue(RcsKeyDirectory.publishTo(context, meA, meB))
        assertNotNull(RcsKeyDirectory.lastPublishedFor(meA))
        assertTrue(sent.any { it.second.contains(RcsE2E.CT_KEY_PACKAGE) || String(it.third).contains(RcsE2E.CT_KEY_PACKAGE) })

        // Key-package request flows also succeed with a sender installed.
        assertTrue(RcsE2E.requestKeyPackage(meB))
        assertTrue(RcsE2E.requestEncryptedChat(context, meA, meB))

        // B's package arrives (cached in memory, as the inbound route does),
        // then a full 1:1 setup sends Welcome + commit through the transport.
        val kpB = onDevice(deviceB) {
            RcsE2E.identityFor(context, meB)
            RcsE2E.freshKeyPackage(context, meB)
        }
        assertNotNull(kpB)
        val ok = RcsE2E.setupGroupWithPeer(context, meA, meB, meB, kpB)
        assertTrue(ok)
        assertNotNull(RcsE2E.groupIdFor(context, meB))
        assertNotNull(RcsE2E.leafIndexFor(context, meB, meB))
        val bodies = sent.map { String(it.third) }
        assertTrue(bodies.any { it.contains(RcsE2E.CT_WELCOME) }, "sent kinds: ${sent.size}")
        assertTrue(bodies.any { it.contains(RcsE2E.CT_COMMIT) })
    }

    @Test fun transportRefusalPropagatesAsFalse() = runTest {
        if (!RustMlsCrypto.isAvailable) return@runTest
        RcsSipTransport.fakeSenderForTest = { _, _, _ -> false }
        val meA = "+15550002001"
        val meB = "+15550002002"
        val kpB = onDevice(deviceB) {
            RcsE2E.identityFor(context, meB)
            RcsE2E.freshKeyPackage(context, meB)
        }
        assertNotNull(kpB)
        // The group is created locally, but every send is refused.
        assertFalse(RcsE2E.setupGroupWithPeer(context, meA, meB, meB, kpB))
        assertNotNull(RcsE2E.groupIdFor(context, meB))
        assertFalse(RcsKeyDirectory.publishTo(context, meA, meB))
    }
}
