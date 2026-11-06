package com.vayunmathur.appstore.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Room entity serialisation: list fields are packed into single columns, so the
 * round trip must preserve order, tolerate blanks, and recover from corrupt rows
 * left by older versions rather than crashing the catalogue read.
 */
class EntityRoundTripTest {

    private fun fullApp() = UnifiedApp(
        packageName = "com.example.app",
        source = AppSource.FDROID,
        name = "Example",
        summary = "Summary",
        description = "Description",
        iconUrl = "https://x.org/icon.png",
        author = "Author",
        categories = listOf("Games", "Tools"),
        versionName = "1.2",
        versionCode = 42L,
        sizeBytes = 1024L,
        apkUrl = "https://x.org/app.apk",
        targetSdk = 35,
        reproducible = true,
        license = "GPL-3.0",
        website = "https://x.org",
        sourceCode = "https://x.org/src",
        screenshots = listOf("https://x.org/1.png", "https://x.org/2.png"),
        featureGraphic = "https://x.org/hero.png",
        rating = 4.5f,
        ratingCount = 100L,
        installs = 5000L,
        updatedOn = "Jul 28, 2026",
        contentRating = "Everyone",
        containsAds = true,
        antiFeatures = listOf("Ads", "Tracking"),
        whatsNew = "Bug fixes",
        addedTimestamp = 1234L,
        tvScreenshots = listOf("https://x.org/tv1.png"),
        requiredFeatures = listOf("android.software.leanback"),
        tvCompatible = true,
        expectedSigners = listOf("aa".repeat(32)),
        apkSha256 = "bb".repeat(32),
        repoUrl = "https://f-droid.org/repo",
    )

    @Test
    fun `a fully populated app survives the round trip`() {
        val app = fullApp()

        assertEquals(app, app.toEntity().toUnifiedApp())
    }

    @Test
    fun `unknown source strings fall back to fdroid`() {
        val entity = fullApp().toEntity().copy(source = "SOME_FUTURE_SOURCE")

        assertEquals(AppSource.FDROID, entity.toUnifiedApp().source)
    }

    @Test
    fun `blank list members are dropped on unpack`() {
        val entity = fullApp().toEntity().copy(
            screenshots = "https://x.org/1.png\n\nhttps://x.org/2.png",
            categories = ",Games,,Tools,",
        )
        val restored = entity.toUnifiedApp()

        assertEquals(listOf("https://x.org/1.png", "https://x.org/2.png"), restored.screenshots)
        assertEquals(listOf("Games", "Tools"), restored.categories)
    }

    @Test
    fun `repo urls normalise their trailing slash`() {
        val entity = fullApp().copy(repoUrl = "https://f-droid.org/repo/").toEntity()

        assertEquals("https://f-droid.org/repo", entity.repoUrl)
    }

    @Test
    fun `missing repo url defaults to fdroid`() {
        val entity = fullApp().copy(repoUrl = null).toEntity()

        assertEquals(DefaultRepos.FDROID.url, entity.repoUrl)
    }

    @Test
    fun `empty signer and feature lists store as null`() {
        val entity = fullApp().copy(expectedSigners = emptyList(), requiredFeatures = emptyList()).toEntity()

        assertNull(entity.expectedSigners)
        assertNull(entity.requiredFeatures)
        assertTrue(entity.toUnifiedApp().expectedSigners.isEmpty())
        assertTrue(entity.toUnifiedApp().requiredFeatures.isEmpty())
    }

    // --- split packing ---

    @Test
    fun `split lists survive including blank hashes`() {
        val splits = listOf(
            SplitFile("base.apk", "https://x.org/base.apk", "cc".repeat(32), 10L),
            SplitFile("cfg.apk", "https://x.org/cfg.apk", null, 0L),
        )

        assertEquals(splits, splits.packSplits().unpackSplits())
    }

    @Test
    fun `empty split lists store as null`() {
        assertNull(emptyList<SplitFile>().packSplits())
        assertTrue((null as String?).unpackSplits().isEmpty())
    }

    @Test
    fun `corrupt split rows are dropped not fatal`() {
        val packed = "only-two\tfields\nbase.apk\thttps://x.org/b.apk\t\tNaN"

        // "only-two fields" has 2 fields -> dropped; NaN size -> 0L, kept.
        val unpacked = packed.unpackSplits()

        assertEquals(1, unpacked.size)
        assertEquals("base.apk", unpacked.single().fileName)
        assertEquals(0L, unpacked.single().size)
    }

    // --- migrations list ---

    @Test
    fun `migrations cover every version up to the current one`() {
        val versions = AppDatabase.migrations.map { it.startVersion to it.endVersion }

        assertEquals(8, versions.size)
        assertTrue(versions.contains(1 to 2))
        assertTrue(versions.contains(8 to 9))
        assertFalse(DB_NAME.isBlank())
    }
}
