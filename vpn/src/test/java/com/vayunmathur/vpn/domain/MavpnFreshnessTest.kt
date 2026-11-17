package com.vayunmathur.vpn.domain

import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MavpnFreshnessTest {
    private fun ms(iso: String): Long = Instant.parse(iso).toEpochMilli()

    private val now = ms("2026-06-01T00:00:00Z")

    @Test fun legacyBundleIsUnknown() {
        assertEquals(BundleFreshnessState.UNKNOWN, classifyFreshness(BundleFreshness.EMPTY, now))
    }

    @Test fun expiredBundleIsOutdatedButIssuedAgeAloneIsNot() {
        val expired = BundleFreshness(
            providerId = "p", revision = 1,
            issuedAtEpochMs = ms("2026-01-01T00:00:00Z"), expiresAtEpochMs = ms("2026-02-01T00:00:00Z"),
        )
        assertEquals(BundleFreshnessState.OUTDATED, classifyFreshness(expired, now))
        // Old issuedAt with no expiry must not be called outdated or current from age alone.
        val oldIssuedOnly = BundleFreshness(issuedAtEpochMs = ms("2020-01-01T00:00:00Z"))
        assertEquals(BundleFreshnessState.UNKNOWN, classifyFreshness(oldIssuedOnly, now))
    }

    @Test fun unexpiredOrRevisionOnlyBundleIsCurrent() {
        val unexpired = BundleFreshness(expiresAtEpochMs = ms("2027-01-01T00:00:00Z"))
        assertEquals(BundleFreshnessState.CURRENT, classifyFreshness(unexpired, now))
        assertEquals(
            BundleFreshnessState.CURRENT,
            classifyFreshness(BundleFreshness(providerId = "p", revision = 3), now),
        )
    }

    @Test fun remoteRevisionAndCheckFailureWin() {
        val local = BundleFreshness(providerId = "p", revision = 2, expiresAtEpochMs = ms("2027-01-01T00:00:00Z"))
        assertEquals(BundleFreshnessState.UPDATE_AVAILABLE, classifyFreshness(local, now, remoteRevision = 3))
        assertEquals(BundleFreshnessState.CURRENT, classifyFreshness(local, now, remoteRevision = 2))
        assertEquals(BundleFreshnessState.CHECK_FAILED, classifyFreshness(local, now, checkFailed = true))
    }

    @Test fun compareUpdateHandlesMismatchAndEqualRevision() {
        val local = BundleFreshness(providerId = "p", revision = 2)
        val remote = MavpnUpdateMetadata("mavpn-update/1", "p", 3)
        assertTrue(compareUpdate(local, remote) is UpdateComparison.UpdateAvailable)
        assertEquals(UpdateComparison.UpToDate, compareUpdate(local, remote.copy(revision = 2)))
        assertTrue(
            compareUpdate(local, remote.copy(providerId = "other")) is UpdateComparison.ProviderMismatch,
        )
    }

    @Test fun parsesUpdateMetadataAndRejectsBadValues() {
        val parsed = parseMavpnUpdateMetadata(
            """{"format":"mavpn-update/1","providerId":"p","revision":4,""" +
                """"issuedAt":"2026-01-01T00:00:00Z","expiresAt":"2027-01-01T00:00:00Z",""" +
                """"downloadUrl":"https://example.invalid/b.mavpn",""" +
                """"sha256":"${"a".repeat(64)}"}""",
        )
        assertEquals(4L, parsed.revision)
        assertEquals("p", parsed.providerId)
        for (bad in listOf(
            """{"format":"mavpn-update/2","providerId":"p","revision":1}""",
            """{"format":"mavpn-update/1","providerId":"p","revision":-1}""",
            """{"format":"mavpn-update/1","providerId":"p","revision":1.5}""",
            """{"format":"mavpn-update/1","providerId":"p","revision":1,"downloadUrl":"http://x.invalid/b"}""",
            """{"format":"mavpn-update/1","providerId":"p","revision":1,"sha256":"zz"}""",
            """{"format":"mavpn-update/1","providerId":"p","revision":1,""" +
                """"issuedAt":"2027-01-01T00:00:00Z","expiresAt":"2026-01-01T00:00:00Z"}""",
        )) {
            assertTrue(runCatching { parseMavpnUpdateMetadata(bad) }.isFailure, bad)
        }
    }

    @Test fun sha256Verification() {
        val bytes = "bundle".encodeToByteArray()
        assertTrue(verifySha256(bytes, sha256Hex(bytes)))
        assertFalse(verifySha256(bytes, sha256Hex("other".encodeToByteArray())))
        assertFalse(verifySha256(bytes, "not-a-hash"))
    }
}
