package com.vayunmathur.appstore.data.security

import com.vayunmathur.appstore.data.AppSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Source-to-profile mapping plus the install-verdict value rules.
 */
class TrustProfileTest {

    @Test
    fun `every source maps to a profile`() {
        assertEquals(TrustProfile.MODERN_APPS, TrustProfile.of(AppSource.MODERN_APPS))
        assertEquals(TrustProfile.FDROID, TrustProfile.of(AppSource.FDROID))
        assertEquals(TrustProfile.IZZY, TrustProfile.of(AppSource.IZZY))
        assertEquals(TrustProfile.PLAY, TrustProfile.of(AppSource.PLAYSTORE))
        assertEquals(TrustProfile.ACCRESCENT, TrustProfile.of(AppSource.ACCRESCENT))
        assertEquals(TrustProfile.PROPRIETARY, TrustProfile.of(AppSource.PROPRIETARY))
    }

    @Test
    fun `every profile has a title summary practice check and limit`() {
        for (profile in TrustProfile.entries) {
            assertTrue(profile.title != 0, profile.name)
            assertTrue(profile.summary != 0, profile.name)
            assertTrue(profile.sourcePractices.isNotEmpty(), profile.name)
            assertTrue(profile.ourChecks.isNotEmpty(), profile.name)
            assertTrue(profile.limits != 0, profile.name)
        }
    }

    @Test
    fun `the store-wide guarantees are non-empty`() {
        assertTrue(StoreGuarantees.rules.isNotEmpty())
        for (rule in StoreGuarantees.rules) {
            assertTrue(rule.title != 0)
            assertTrue(rule.detail != 0)
        }
    }

    // --- VerificationResult value rules ---

    @Test
    fun `only rejection blocks the install`() {
        assertTrue(VerificationResult.Verified("ok").allowsInstall)
        assertTrue(VerificationResult.Unverified("no key published").allowsInstall)
        assertFalse(VerificationResult.Rejected("hash mismatch").allowsInstall)
    }

    @Test
    fun `a rejection carries no stamp to pin`() {
        assertNull(VerificationResult.Rejected("gone").stamp)
    }

    @Test
    fun `verified and unverified surface the stamp to pin`() {
        assertEquals("abc", VerificationResult.Verified("ok", stampToPin = "abc").stamp)
        assertEquals("abc", VerificationResult.Unverified("warn", stampToPin = "abc").stamp)
        assertNull(VerificationResult.Verified("ok").stamp)
        assertNull(VerificationResult.Unverified("warn").stamp)
    }

    // --- InstallRequirement defaults are no-op gates ---

    @Test
    fun `default requirement only names the package`() {
        val requirement = InstallRequirement(expectedPackage = "com.example.app")

        assertTrue(requirement.requiredSigners.isEmpty())
        assertTrue(requirement.expectedSha256.isEmpty())
        assertNull(requirement.pinnedStamp)
        assertFalse(requirement.requireStamp)
        assertNull(requirement.minVersionCode)
        assertFalse(requirement.requireTvCompatible)
    }
}
