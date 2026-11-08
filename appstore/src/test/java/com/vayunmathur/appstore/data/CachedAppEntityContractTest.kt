package com.vayunmathur.appstore.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * The cached-app row as a value: a fully populated entity keeps every
 * field through copy, and equality distinguishes rows by their fields.
 * The Room layer stores exactly this shape; toEntity/toUnifiedApp
 * round trips are covered separately.
 */
class CachedAppEntityContractTest {

    private fun full() = CachedAppEntity(
        packageName = "com.example.full",
        source = "FDROID",
        name = "Full",
        summary = "Summary",
        description = "Description",
        iconUrl = "https://example.org/icon.png",
        author = "Author",
        categories = "Tools,Media",
        versionName = "1.2",
        versionCode = 42L,
        sizeBytes = 1234L,
        apkUrl = "https://example.org/app.apk",
        targetSdk = 35,
        reproducible = true,
        repoUrl = "https://f-droid.org/repo",
        lastUpdated = 99L,
        expectedSigners = "ab12,cd34",
        apkSha256 = "ff".repeat(32),
        license = "MIT",
        website = "https://example.org",
        sourceCode = "https://example.org/src",
        screenshots = "https://example.org/1.png",
        featureGraphic = "https://example.org/fg.png",
        rating = 4.5f,
        ratingCount = 100L,
        installs = 5000L,
        updatedOn = "Nov 2026",
        contentRating = "Everyone",
        containsAds = true,
        antiFeatures = "Tracking",
        whatsNew = "New stuff",
        addedTimestamp = 7L,
        tvScreenshots = "https://example.org/tv.png",
        requiredFeatures = "android.hardware.touchscreen",
        tvCompatible = true,
        splitFiles = "https://example.org/s.apk|10|ab",
    )

    @Test
    fun `a fully populated entity keeps its fields through copy`() {
        val entity = full()
        val copy = entity.copy(versionCode = 43L)
        assertEquals(43L, copy.versionCode)
        assertEquals(entity.packageName, copy.packageName)
        assertEquals(entity.expectedSigners, copy.expectedSigners)
        assertEquals(entity.splitFiles, copy.splitFiles)
        assertNotEquals(entity, copy)
        assertEquals(entity, full())
        assertEquals(entity.hashCode(), full().hashCode())
        assertTrue(entity.toString().contains("com.example.full"))
    }
}
