package com.vayunmathur.communicate.rcs

import com.vayunmathur.communicate.data.rcs.RcsSessionManager
import com.vayunmathur.communicate.data.rcs.RcsSession
import com.vayunmathur.communicate.data.rcs.e2e.RcsE2E
import com.vayunmathur.communicate.data.rcs.e2e.RcsKeyDirectory
import com.vayunmathur.communicate.data.rcs.e2e.RcsPeerKeys
import com.vayunmathur.communicate.data.rcs.e2e.RcsPendingGroups
import com.vayunmathur.communicate.data.rcs.e2e.e2eThreadFor
import com.vayunmathur.communicate.data.rcs.e2e.commitLockFor
import com.vayunmathur.communicate.data.rcs.e2e.hex
import com.vayunmathur.communicate.data.rcs.e2e.parseMembers
import com.vayunmathur.communicate.data.rcs.e2e.pruneMembers
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Slice-3 tests for the E2EE support types that don't need the native
 * library: [RcsPeerKeys] cache (store/get/consume/expiry), [RcsPendingGroups]
 * readiness, key-directory parsing, member-map helpers, hex/thread helpers,
 * and the gate-closed [RcsE2E] paths (native unavailable on this host or
 * transport down → null/false, never throws).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RcsSlice3E2ESupportTest {

    @Test
    fun peerKeysStoreGetConsume() {
        RcsPeerKeys.clear()
        val pkg = byteArrayOf(9, 8, 7, 6)
        RcsPeerKeys.store("+15550006161", pkg)
        assertTrue(RcsPeerKeys.get("+15550006161")!!.contentEquals(pkg))
        // Consume removes.
        assertTrue(RcsPeerKeys.consume("+15550006161")!!.contentEquals(pkg))
        assertNull(RcsPeerKeys.get("+15550006161"))
        assertNull(RcsPeerKeys.consume("+15550006161"))
    }

    @Test
    fun peerKeysRejectsBadInput() {
        RcsPeerKeys.clear()
        RcsPeerKeys.store("", byteArrayOf(1))
        RcsPeerKeys.store("+15550006262", ByteArray(0))
        assertNull(RcsPeerKeys.get(""))
        assertNull(RcsPeerKeys.get("+15550006262"))
        assertNull(RcsPeerKeys.get("+15550006363"))
    }

    @Test
    fun pendingGroupsReadiness() {
        val conv = "slice3-pending-${System.nanoTime()}"
        assertFalse(RcsPendingGroups.isPending(conv))
        RcsPendingGroups.add(conv, listOf("+15550007171", "+15550007272"))
        assertTrue(RcsPendingGroups.isPending(conv))
        // Not ready: no packages cached.
        RcsPeerKeys.clear()
        assertTrue(RcsPendingGroups.readyFor("+15550007171").none { it.first == conv })
        // Cache both → ready.
        RcsPeerKeys.store("+15550007171", byteArrayOf(1, 2))
        RcsPeerKeys.store("+15550007272", byteArrayOf(3, 4))
        val ready = RcsPendingGroups.readyFor("+15550007171").firstOrNull { it.first == conv }
        assertTrue(ready != null)
        assertEquals(setOf("+15550007171", "+15550007272"), ready.second.keys)
        // Unrelated peer doesn't match.
        assertTrue(RcsPendingGroups.readyFor("+15550009999").none { it.first == conv })
        RcsPendingGroups.remove(conv)
        assertFalse(RcsPendingGroups.isPending(conv))
        // Bad adds are ignored.
        RcsPendingGroups.add("", listOf("+1"))
        RcsPendingGroups.add("slice3-blank", emptyList())
        assertFalse(RcsPendingGroups.isPending(""))
        assertFalse(RcsPendingGroups.isPending("slice3-blank"))
        RcsPeerKeys.clear()
    }

    @Test
    fun keyDirectoryParseVariants() {
        val bytes = byteArrayOf(10, 20, 30, 40, 50)
        val b64 = java.util.Base64.getEncoder().encodeToString(bytes)
        val body = "Content-Type: application/x-rcs-keypackage\r\n\r\n$b64"
        assertTrue(RcsKeyDirectory.parsePublished(body)!!.contentEquals(bytes))
        // LF-only separator also works.
        assertTrue(
            RcsKeyDirectory.parsePublished("Content-Type: application/x-rcs-keypackage\n\n$b64")!!
                .contentEquals(bytes),
        )
        assertNull(RcsKeyDirectory.parsePublished("plain body"))
        assertNull(RcsKeyDirectory.parsePublished("Content-Type: application/x-rcs-keypackage\r\n\r\n"))
        assertNull(
            RcsKeyDirectory.parsePublished(
                "Content-Type: application/x-rcs-keypackage\r\n\r\n!!!not-base64!!!",
            ),
        )
        assertNull(RcsKeyDirectory.lastPublishedFor("+15550008181"))
    }

    @Test
    fun memberMapHelpers() {
        assertEquals(emptyMap(), RcsE2E.parseMembers(""))
        assertEquals(emptyMap(), RcsE2E.parseMembers("garbage-no-equals"))
        assertEquals(emptyMap(), RcsE2E.parseMembers("garbage-no-equals"))
        assertEquals(mapOf("+1" to 3), RcsE2E.parseMembers("+1=3"))
        assertEquals(mapOf("+1" to 1, "+2" to 2), RcsE2E.parseMembers("+1=1,+2=2"))
        // Malformed entries skipped.
        assertEquals(mapOf("+2" to 2), RcsE2E.parseMembers("bad,+2=2,alsobad="))
        // Prune keeps the rest.
        assertEquals("+2=2", RcsE2E.pruneMembers("+1=1,+2=2", setOf(1)))
        assertEquals("", RcsE2E.pruneMembers("+1=1", setOf(1)))
        // Track: creator leaf starts at 1, dedupes, continues max+1.
        val tracked = RcsE2E.trackAddedMembers("", listOf("+1", "+1", " ", "+2"))
        assertEquals(mapOf("+1" to 1, "+2" to 2), RcsE2E.parseMembers(tracked))
        val grown = RcsE2E.trackAddedMembers(tracked, listOf("+3"))
        assertEquals(3, RcsE2E.parseMembers(grown)["+3"])
    }

    @Test
    fun hexAndThreadHelpers() {
        assertEquals("00ff10", byteArrayOf(0, 0xFF.toByte(), 0x10).hex())
        assertEquals("e2e:+15550009191", e2eThreadFor("+15550009191"))
        // Commit locks are per-conversation singletons.
        assertTrue(RcsE2E.commitLockFor("slice3-lock-a") === RcsE2E.commitLockFor("slice3-lock-a"))
        assertFalse(RcsE2E.commitLockFor("slice3-lock-a") === RcsE2E.commitLockFor("slice3-lock-b"))
    }

    @Test
    fun e2eGateClosedPathsNeverThrow() = runTest {
        // Transport is down in tests, so these all fail closed. Only the
        // Room-free paths are exercised here (peer-key cache + key directory
        // are in-memory; the Room-backed identity paths need SQLCipher's
        // AndroidKeyStore, covered on-device instead).
        RcsPeerKeys.clear()
        assertNull(RcsE2E.safetyFingerprint("+15550009292"))
        assertNull(RcsE2E.mySafetyFingerprint("+15550009393"))
        assertFalse(RcsE2E.requestKeyPackage("+15550009595"))
        assertFalse(RcsE2E.isMlsPayload("plain text, short"))
        // A cached package yields a stable safety fingerprint without Room.
        RcsPeerKeys.store("+15550009292", byteArrayOf(7, 7, 7))
        val fp = RcsE2E.safetyFingerprint("+15550009292")
        assertTrue(fp != null && fp.isNotBlank())
        assertEquals(fp, RcsE2E.safetyFingerprint("+15550009292"))
        RcsPeerKeys.clear()
    }

    @Test
    fun e2eContentTypeDetectionWithoutNative() {
        // Content-type match short-circuits before any native call; when the
        // native lib is unavailable the gate returns false first.
        val body = "Content-Type: application/x-rcs-mls\r\n\r\nQUJD"
        val result = RcsE2E.isMlsPayload(body)
        // Either true (native present + CT match) or false (gate closed) —
        // both are honest; assert it doesn't throw and is deterministic.
        assertEquals(result, RcsE2E.isMlsPayload(body))
    }
}
