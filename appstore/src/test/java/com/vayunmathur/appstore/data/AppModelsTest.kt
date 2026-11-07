package com.vayunmathur.appstore.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Catalogue model rules: source precedence, per-package hero image choice,
 * the ranking view of a listing, and the fixed repo set.
 */
class AppModelsTest {

    // --- AppSource.priority ---

    @Test
    fun `own repo outranks every other source`() {
        assertTrue(AppSource.MODERN_APPS.priority < AppSource.PROPRIETARY.priority)
        assertTrue(AppSource.PROPRIETARY.priority < AppSource.FDROID.priority)
        assertTrue(AppSource.FDROID.priority < AppSource.IZZY.priority)
        assertTrue(AppSource.IZZY.priority < AppSource.ACCRESCENT.priority)
        assertTrue(AppSource.ACCRESCENT.priority < AppSource.PLAYSTORE.priority)
    }

    @Test
    fun `priority order matches the declared list`() {
        AppSource.PRIORITY.forEachIndexed { index, source ->
            assertEquals(index, source.priority)
        }
    }

    @Test
    fun `default install enables only the two first-party repos`() {
        assertEquals(
            setOf(AppSource.MODERN_APPS, AppSource.PROPRIETARY),
            AppSource.DEFAULT_ENABLED,
        )
    }

    @Test
    fun `toggleable lists every source exactly once`() {
        assertEquals(AppSource.entries.toSet(), AppSource.TOGGLEABLE.toSet())
        assertEquals(AppSource.entries.size, AppSource.TOGGLEABLE.size)
    }

    // --- UnifiedApp.heroImage ---

    private fun app(
        screenshots: List<String> = emptyList(),
        featureGraphic: String? = null,
    ) = UnifiedApp(
        packageName = "com.example.app",
        source = AppSource.FDROID,
        name = "Example",
        screenshots = screenshots,
        featureGraphic = featureGraphic,
    )

    @Test
    fun `hero image prefers the feature graphic over screenshots`() {
        assertEquals("https://x.org/hero.png", app(listOf("https://x.org/1.png"), "https://x.org/hero.png").heroImage)
    }

    @Test
    fun `hero image falls back to the first screenshot`() {
        assertEquals("https://x.org/1.png", app(listOf("https://x.org/1.png", "https://x.org/2.png")).heroImage)
    }

    @Test
    fun `hero image is null with no artwork at all`() {
        assertNull(app().heroImage)
    }

    // --- UnifiedApp.searchCandidate ---

    @Test
    fun `search candidate carries the ranking fields`() {
        val candidate = UnifiedApp(
            packageName = "com.example.app",
            source = AppSource.PLAYSTORE,
            name = "Example App",
            summary = "Does things",
            author = "Example Org",
            installs = 42L,
        ).searchCandidate()

        assertEquals("Example App", candidate.name)
        assertEquals("com.example.app", candidate.packageName)
        assertEquals("Example Org", candidate.author)
        assertEquals("Does things", candidate.summary)
        assertEquals(42L, candidate.popularity)
    }

    @Test
    fun `search candidate tolerates a missing author`() {
        val candidate = UnifiedApp(
            packageName = "com.example.app",
            source = AppSource.FDROID,
            name = "Example",
        ).searchCandidate()

        assertEquals("", candidate.author)
    }

    // --- DefaultRepos ---

    @Test
    fun `every repo pins a 64-hex fingerprint`() {
        val hex = Regex("[0-9a-f]{64}")
        for (repo in DefaultRepos.ALL) {
            assertTrue(hex.matches(repo.pinnedFingerprint), repo.displayName)
            assertTrue(repo.url.startsWith("https://"), repo.displayName)
        }
    }

    @Test
    fun `repo set covers the four offline sources`() {
        assertEquals(
            setOf(AppSource.FDROID, AppSource.MODERN_APPS, AppSource.PROPRIETARY, AppSource.IZZY),
            DefaultRepos.ALL.map { it.source }.toSet(),
        )
    }

    @Test
    fun `only fdroid advertises a reproducibility feed`() {
        assertTrue(DefaultRepos.FDROID.supportsReproducibilityFeed)
        assertFalse(DefaultRepos.MODERN_APPS.supportsReproducibilityFeed)
        assertFalse(DefaultRepos.PROPRIETARY.supportsReproducibilityFeed)
        assertFalse(DefaultRepos.IZZY.supportsReproducibilityFeed)
    }

    @Test
    fun `descriptor converts to an enabled entity`() {
        val entity = DefaultRepos.FDROID.toEntity()

        assertEquals(DefaultRepos.FDROID.url, entity.url)
        assertEquals(DefaultRepos.FDROID.displayName, entity.name)
        assertTrue(entity.enabled)
        assertEquals(DefaultRepos.FDROID.pinnedFingerprint, entity.fingerprint)
    }

    // --- SyncReport ---

    @Test
    fun `a clean full sync reports no failure`() {
        val report = SyncReport(fdroidCount = 10, modernCount = 5, proprietaryCount = 2, izzyCount = 3)

        assertFalse(report.anyFailed)
        assertFalse(report.allSkipped)
    }

    @Test
    fun `a failed source counts unless it was skipped`() {
        val failed = SyncReport(fdroidCount = null, modernCount = 5, proprietaryCount = 2, izzyCount = 3)
        assertTrue(failed.anyFailed)

        val skipped = failed.copy(skipped = setOf(AppSource.FDROID))
        assertFalse(skipped.anyFailed)
    }

    @Test
    fun `all sources off reads as entirely skipped`() {
        val report = SyncReport(
            skipped = setOf(AppSource.MODERN_APPS, AppSource.PROPRIETARY, AppSource.FDROID, AppSource.IZZY),
        )

        assertTrue(report.allSkipped)
        assertFalse(report.anyFailed)
    }

    @Test
    fun `a partial skip leaves the rest reportable`() {
        val report = SyncReport(
            fdroidCount = 10,
            modernCount = 5,
            proprietaryCount = 2,
            skipped = setOf(AppSource.IZZY),
        )

        assertFalse(report.allSkipped)
        // izzyCount is null but skipped, so no failure; every other source answered.
        assertFalse(report.anyFailed)
    }
}
