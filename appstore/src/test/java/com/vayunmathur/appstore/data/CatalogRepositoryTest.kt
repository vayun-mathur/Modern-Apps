package com.vayunmathur.appstore.data

import androidx.room3.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.test.core.app.ApplicationProvider
import android.content.Context
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Catalogue reads against a real in-memory Room DB: the SQL (category
 * matching, search, recent ordering) is the behaviour under test, so a fake
 * DAO would hide exactly what can break.
 */
@RunWith(RobolectricTestRunner::class)
class CatalogRepositoryTest {

    private lateinit var db: AppDatabase
    private lateinit var scope: CoroutineScope
    private lateinit var repo: CatalogRepository

    private fun entity(
        pkg: String,
        source: AppSource = AppSource.FDROID,
        name: String = pkg,
        categories: List<String> = emptyList(),
        versionCode: Long = 1L,
        lastUpdated: Long = 0L,
        repoUrl: String = DefaultRepos.FDROID.url,
        summary: String = "",
    ) = UnifiedApp(
        packageName = pkg,
        source = source,
        name = name,
        summary = summary,
        categories = categories,
        versionCode = versionCode,
        repoUrl = repoUrl,
    ).toEntity().copy(lastUpdated = lastUpdated)

    @BeforeTest
    fun setUp() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(ctx, AppDatabase::class.java)
            .setDriver(BundledSQLiteDriver())
            .allowMainThreadQueries()
            .build()
        scope = CoroutineScope(Dispatchers.Unconfined)
        repo = CatalogRepository(ctx, db, scope)
    }

    @AfterTest
    fun tearDown() {
        scope.cancel()
        db.close()
    }

    private suspend fun seed(vararg rows: CachedAppEntity) = db.cachedAppDao().upsertAll(rows.toList())

    @Test
    fun `byPackage returns the cached row and null for unknown packages`() = runTest {
        seed(entity("com.example.a", name = "Alpha"))
        assertEquals("Alpha", repo.byPackage("com.example.a")?.name)
        assertNull(repo.byPackage("com.example.missing"))
    }

    @Test
    fun `byPackages skips empty input and returns only known packages`() = runTest {
        seed(entity("com.example.a"), entity("com.example.b"))
        assertTrue(repo.byPackages(emptyList()).isEmpty())
        assertEquals(
            setOf("com.example.a", "com.example.b"),
            repo.byPackages(listOf("com.example.a", "com.example.b", "com.example.z")).map { it.packageName }.toSet(),
        )
    }

    @Test
    fun `allBySource returns only that source sorted by name`() = runTest {
        seed(
            entity("com.example.z", name = "Zulu"),
            entity("com.example.a", name = "Alpha"),
            entity("com.example.m", source = AppSource.MODERN_APPS, name = "Modern", repoUrl = DefaultRepos.MODERN_APPS.url),
        )
        assertEquals(listOf("Alpha", "Zulu"), repo.allBySource(AppSource.FDROID).map { it.name })
        assertEquals(listOf("Modern"), repo.allBySource(AppSource.MODERN_APPS).map { it.name })
    }

    @Test
    fun `category queries match whole categories not substrings`() = runTest {
        seed(
            entity("com.example.game", categories = listOf("Games")),
            entity("com.example.gamey", categories = listOf("GamesExtra")),
        )
        assertEquals(listOf("com.example.game"), repo.byCategory("Games").map { it.packageName })
        assertEquals(listOf("com.example.game"), repo.allByCategory("Games").map { it.packageName })
    }

    @Test
    fun `categories are ordered by popularity then name and drop blanks`() = runTest {
        seed(
            entity("com.example.a", categories = listOf("Games", "Tools")),
            entity("com.example.b", categories = listOf("Games")),
            entity("com.example.c", categories = listOf("Tools", "")),
        )
        assertEquals(listOf("Games", "Tools"), repo.categories())
    }

    @Test
    fun `recentlyUpdatedBySource honours source and limit newest first`() = runTest {
        seed(
            entity("com.example.old", lastUpdated = 10L),
            entity("com.example.new", lastUpdated = 30L),
            entity("com.example.mid", lastUpdated = 20L),
        )
        assertEquals(
            listOf("com.example.new", "com.example.mid"),
            repo.recentlyUpdatedBySource(AppSource.FDROID, limit = 2).map { it.packageName },
        )
    }

    @Test
    fun `searchLocal matches name and per-word tokens but not blank queries`() = runTest {
        seed(
            entity("com.example.chase", name = "Chase Mobile", summary = "banking"),
            entity("com.example.other", name = "Other", summary = "nothing"),
        )
        assertTrue(repo.searchLocal("   ").isEmpty())
        assertEquals(listOf("com.example.chase"), repo.searchLocal("Chase Mobile").map { it.packageName })
        // Two words apart in the row still find it via per-word queries.
        assertEquals(listOf("com.example.chase"), repo.searchLocal("mobile chase").map { it.packageName })
        assertTrue(repo.searchLocal("nonexistentword").isEmpty())
    }

    @Test
    fun `updatesFor only returns installed apps with a newer catalogue version`() = runTest {
        seed(
            entity("com.example.stale", versionCode = 5L),
            entity("com.example.current", versionCode = 3L),
        )
        val installed = listOf(
            InstalledInfo("com.example.stale", "Stale", "1.0", versionCode = 4L),
            InstalledInfo("com.example.current", "Current", "1.0", versionCode = 3L),
            InstalledInfo("com.example.unknown", "Unknown", "1.0", versionCode = 1L),
        )
        assertEquals(listOf("com.example.stale"), repo.updatesFor(installed).map { it.packageName })
        assertTrue(repo.updatesFor(emptyList()).isEmpty())
    }

    @Test
    fun `indexSnapshot and sourceOf reflect cached rows`() = runTest {
        seed(entity("com.example.a", source = AppSource.IZZY, versionCode = 7L, repoUrl = DefaultRepos.IZZY.url))
        val snapshot = repo.indexSnapshot()
        assertEquals(7L, snapshot["com.example.a"]?.versionCode)
        assertEquals(AppSource.IZZY, repo.sourceOf("com.example.a"))
        assertNull(repo.sourceOf("com.example.missing"))
    }

    @Test
    fun `sync with no sources enabled skips everything and seeds default repos`() = runTest {
        seed(entity("com.example.a"))
        val steps = mutableListOf<SyncStep>()
        val report = repo.sync(enabled = emptySet()) { steps += it }
        assertTrue(report.allSkipped)
        assertFalse(report.anyFailed)
        assertEquals(AppSource.entries.toSet().intersect(DefaultRepos.ALL.map { it.source }.toSet()), report.skipped)
        assertTrue(steps.isEmpty())
        // Default repos were seeded even though every sync was skipped.
        assertEquals(DefaultRepos.ALL.map { it.url }.toSet(), db.repoDao().all().map { it.url }.toSet())
        // The disabled FDroid rows were purged.
        assertTrue(repo.byPackages(listOf("com.example.a")).isEmpty())
    }

    @Test
    fun `purgeDisabled drops only disabled sources rows`() = runTest {
        seed(
            entity("com.example.fdroid"),
            entity("com.example.modern", source = AppSource.MODERN_APPS, repoUrl = DefaultRepos.MODERN_APPS.url),
        )
        repo.purgeDisabled(setOf(AppSource.MODERN_APPS))
        assertNull(repo.byPackage("com.example.fdroid"))
        assertEquals("com.example.modern", repo.byPackage("com.example.modern")?.packageName)
    }

    @Test
    fun `repos and modernApps flows emit seeded rows`() = runTest {
        db.repoDao().upsert(RepoEntity(url = "https://example.org/repo", name = "Example"))
        seed(entity("com.example.modern", source = AppSource.MODERN_APPS, name = "Modern", repoUrl = DefaultRepos.MODERN_APPS.url))
        assertEquals(listOf("Example"), repo.repos.first { it.isNotEmpty() }.map { it.name })
        assertEquals(listOf("Modern"), repo.modernApps.first { it.isNotEmpty() }.map { it.name })
        assertEquals(
            "com.example.modern",
            repo.packageIndex.first { it.isNotEmpty() }["com.example.modern"]?.packageName,
        )
    }

    @Test
    fun `syncReport anyFailed reflects enabled sources only`() {
        assertTrue(SyncReport().anyFailed)
        assertFalse(SyncReport(fdroidCount = 1, modernCount = 2, proprietaryCount = 3, izzyCount = 4).anyFailed)
        assertFalse(SyncReport(skipped = DefaultRepos.ALL.map { it.source }.toSet()).anyFailed)
        assertTrue(SyncReport(skipped = setOf(AppSource.FDROID)).anyFailed)
    }
}
