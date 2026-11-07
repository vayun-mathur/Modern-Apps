package com.vayunmathur.appstore.data.play

import com.aurora.gplayapi.data.models.App
import com.aurora.gplayapi.data.models.Artwork
import com.aurora.gplayapi.data.models.ContentRating
import com.aurora.gplayapi.data.models.EncodedCertificateSet
import com.aurora.gplayapi.data.models.Rating
import com.vayunmathur.appstore.data.AppSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Play's App model → UnifiedApp mapping: blank Play fields fall back or
 * clear rather than rendering as empty strings, the rating count is the
 * sum of the star buckets, and certificate fingerprints are normalised
 * (hex stays hex, base64url decodes, garbage drops).
 */
// Robolectric for android.util.Base64, which fingerprint normalisation uses.
@RunWith(RobolectricTestRunner::class)
class PlayAppMappingTest {

    @Test
    fun `a fully populated play app maps every shown field`() {
        val app = App(
            packageName = "com.example.play",
            displayName = "Play App",
            shortDescription = "Short summary",
            description = "Long description",
            iconArtwork = Artwork(url = "https://example.org/icon.png"),
            coverArtwork = Artwork(url = "https://example.org/cover.png"),
            screenshots = listOf(
                Artwork(url = "https://example.org/1.png"),
                Artwork(url = ""),
                Artwork(url = "https://example.org/2.png"),
            ),
            developerName = "Play Dev",
            categoryName = "Tools",
            versionName = "3.1",
            versionCode = 31L,
            size = 5_000_000L,
            changes = "What's new text",
            targetSdk = 35,
            rating = Rating(
                average = 4.2f,
                oneStar = 1L, twoStar = 2L, threeStar = 3L, fourStar = 4L, fiveStar = 5L,
            ),
            installs = 9000L,
            updatedOn = "Nov 1, 2026",
            contentRating = ContentRating(title = "Everyone"),
            permissions = listOf("android.permission.CAMERA"),
            containsAds = true,
            certificateSetList = listOf(
                EncodedCertificateSet(certificateSet = "", sha256 = "AB".repeat(32)),
            ),
        )

        val unified = app.toUnifiedApp()
        assertEquals("com.example.play", unified.packageName)
        assertEquals(AppSource.PLAYSTORE, unified.source)
        assertEquals("Play App", unified.name)
        assertEquals("Short summary", unified.summary)
        assertEquals("Long description", unified.description)
        assertEquals("https://example.org/icon.png", unified.iconUrl)
        assertEquals("https://example.org/cover.png", unified.featureGraphic)
        assertEquals(
            listOf("https://example.org/1.png", "https://example.org/2.png"),
            unified.screenshots,
        )
        assertEquals("Play Dev", unified.author)
        assertEquals(listOf("Tools"), unified.categories)
        assertEquals("3.1", unified.versionName)
        assertEquals(31L, unified.versionCode)
        assertEquals(5_000_000L, unified.sizeBytes)
        assertEquals("What's new text", unified.whatsNew)
        assertEquals(35, unified.targetSdk)
        assertEquals(4.2f, unified.rating)
        assertEquals(15L, unified.ratingCount)
        assertEquals(9000L, unified.installs)
        assertEquals("Everyone", unified.contentRating)
        assertEquals(listOf("android.permission.CAMERA"), unified.permissions)
        assertTrue(unified.containsAds)
        assertEquals(
            "https://play.google.com/store/apps/details?id=com.example.play",
            unified.website,
        )
        assertEquals(listOf("ab".repeat(32)), unified.expectedSigners)
    }

    @Test
    fun `blank play fields fall back instead of rendering empty`() {
        val unified = App(packageName = "com.example.bare").toUnifiedApp()
        // No display name: the last package segment is the name.
        assertEquals("bare", unified.name)
        assertEquals("", unified.summary)
        assertEquals("", unified.description)
        assertNull(unified.iconUrl)
        assertNull(unified.versionName)
        // gplayapi's own default targetSdk is 21, which the mapping passes through.
        assertEquals(21, unified.targetSdk)
        assertNull(unified.rating)
        assertEquals(0L, unified.ratingCount)
        assertTrue(unified.categories.isEmpty())
        assertTrue(unified.expectedSigners.isEmpty())
    }

    @Test
    fun `description falls back to the short description`() {
        val unified = App(
            packageName = "com.example.fallback",
            shortDescription = "Only short",
            description = "",
        ).toUnifiedApp()
        assertEquals("Only short", unified.description)
    }

    @Test
    fun `undecodable certificate fingerprints are dropped and duplicates merged`() {
        val app = App(
            packageName = "com.example.certs",
            certificateSetList = listOf(
                EncodedCertificateSet(certificateSet = "", sha256 = "cd".repeat(32)),
                EncodedCertificateSet(certificateSet = "", sha256 = "CD".repeat(32)),
                EncodedCertificateSet(certificateSet = "", sha256 = "not-a-fingerprint"),
            ),
        )
        assertEquals(listOf("cd".repeat(32)), app.toUnifiedApp().expectedSigners)
    }
}
