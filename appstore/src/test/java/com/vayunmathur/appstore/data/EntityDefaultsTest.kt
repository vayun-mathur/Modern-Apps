package com.vayunmathur.appstore.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Defaulted columns of the Room entities: a row built from only the
 * required fields must read back the documented defaults, so a source
 * that publishes nothing optional caches as "unknown", not as a claim.
 */
class EntityDefaultsTest {

    @Test
    fun `cached app built from required fields only carries the documented defaults`() {
        val entity = CachedAppEntity(
            packageName = "com.example.minimal",
            source = "FDROID",
            name = "Minimal",
            summary = "Summary",
            description = "Description",
            iconUrl = null,
            author = null,
            categories = "",
            versionName = null,
            versionCode = 1L,
            sizeBytes = 0L,
            apkUrl = null,
            targetSdk = null,
            repoUrl = null,
            lastUpdated = 0L,
        )
        assertFalse(entity.reproducible)
        assertNull(entity.expectedSigners)
        assertNull(entity.apkSha256)
        assertNull(entity.license)
        assertNull(entity.website)
        assertNull(entity.sourceCode)
        assertNull(entity.screenshots)
        assertNull(entity.featureGraphic)
        assertNull(entity.rating)
        assertEquals(0L, entity.ratingCount)
        assertEquals(0L, entity.installs)
        assertNull(entity.updatedOn)
        assertNull(entity.contentRating)
        assertFalse(entity.containsAds)
        assertNull(entity.antiFeatures)
        assertNull(entity.whatsNew)
        assertEquals(0L, entity.addedTimestamp)
        assertNull(entity.tvScreenshots)
        assertNull(entity.requiredFeatures)
        assertNull(entity.tvCompatible)
        assertNull(entity.splitFiles)
        // The minimal row still round-trips to a UnifiedApp with empty lists.
        val unified = entity.toUnifiedApp()
        assertEquals("com.example.minimal", unified.packageName)
        assertTrue(unified.categories.isEmpty())
        assertTrue(unified.screenshots.isEmpty())
        assertTrue(unified.splitFiles.isEmpty())
    }

    @Test
    fun `repo entity defaults to enabled and never synced`() {
        val repo = RepoEntity(url = "https://example.org/repo", name = "Example")
        assertTrue(repo.enabled)
        assertNull(repo.fingerprint)
        assertEquals(0L, repo.lastSync)
        assertEquals(repo, repo.copy())
    }

    @Test
    fun `pinned stamp entity keeps package stamp and first seen`() {
        val pin = PinnedStampEntity(
            packageName = "com.example.pinned",
            stampSha256 = "ab".repeat(32),
            firstSeen = 123L,
        )
        assertEquals("com.example.pinned", pin.packageName)
        assertEquals(64, pin.stampSha256.length)
        assertEquals(123L, pin.firstSeen)
        assertEquals(pin, pin.copy())
        assertEquals(pin.hashCode(), pin.copy().hashCode())
    }
}
