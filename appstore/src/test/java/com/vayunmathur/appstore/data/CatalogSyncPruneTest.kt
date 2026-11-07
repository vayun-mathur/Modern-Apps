package com.vayunmathur.appstore.data

import android.content.Context
import androidx.room3.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.test.core.app.ApplicationProvider
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.runTest
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * A sync prunes what older configurations left behind: a RepoEntity
 * whose URL is no longer a default repo is deleted together with its
 * cached rows, the default repos are seeded, and the report marks
 * every offline source as skipped when none is enabled.
 */
@RunWith(RobolectricTestRunner::class)
class CatalogSyncPruneTest {

    private lateinit var db: AppDatabase
    private lateinit var scope: CoroutineScope
    private lateinit var catalog: CatalogRepository

    @BeforeTest
    fun setUp() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(ctx, AppDatabase::class.java)
            .setDriver(BundledSQLiteDriver())
            .allowMainThreadQueries()
            .build()
        scope = CoroutineScope(Dispatchers.IO)
        catalog = CatalogRepository(ctx, db, scope)
    }

    @Test
    fun `sync prunes legacy repos and their rows and seeds the defaults`() = runTest {
        val legacyUrl = "https://example.org/legacy-repo"
        db.repoDao().upsert(RepoEntity(url = legacyUrl, name = "Legacy"))
        db.cachedAppDao().upsert(
            UnifiedApp(
                packageName = "com.example.legacy",
                source = AppSource.FDROID,
                name = "Legacy App",
                repoUrl = legacyUrl,
            ).toEntity(),
        )
        db.cachedAppDao().upsert(
            UnifiedApp(
                packageName = "com.example.legacy2",
                source = AppSource.FDROID,
                name = "Legacy Two",
                repoUrl = DefaultRepos.LEGACY_MODERN_APPS_REPO_KEY,
            ).toEntity(),
        )

        val report = catalog.sync(emptySet())

        assertTrue(report.allSkipped)
        assertEquals(
            DefaultRepos.ALL.map { it.source }.toSet(),
            report.skipped,
        )
        val repos = db.repoDao().all()
        assertTrue(repos.none { it.url == legacyUrl }, "legacy repo survived: $repos")
        assertTrue(repos.map { it.url }.containsAll(DefaultRepos.ALL.map { it.url }))
        assertTrue(db.cachedAppDao().all().isEmpty(), "legacy rows survived")
        scope.cancel()
    }

    @Test
    fun `sync with every source enabled reports each in progress order`() = runTest {
        val steps = mutableListOf<SyncStep>()
        val report = catalog.sync(AppSource.entries.toSet()) { steps += it }
        // Offline every provider fails, so every count is null and the
        // run reports failure rather than a partial success.
        assertEquals(
            listOf(SyncStep.IZZY, SyncStep.FDROID, SyncStep.PROPRIETARY, SyncStep.MODERN_APPS),
            steps,
        )
        assertTrue(report.anyFailed)
        assertTrue(report.skipped.isEmpty())
        scope.cancel()
    }
}
