package com.vayunmathur.appstore.util

import android.app.Application
import android.os.Looper
import androidx.room3.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.appstore.data.AppDatabase
import com.vayunmathur.appstore.data.AppSource
import com.vayunmathur.appstore.data.DefaultRepos
import com.vayunmathur.appstore.data.UnifiedApp
import com.vayunmathur.appstore.data.toEntity
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows

/**
 * Phone loadHome: recent rows per enabled offline source reach the
 * home state, and disabling every source clears them. Play is kept
 * out of the enabled set — its rows need a session this test has not
 * got, and loadHome must leave the offline rows alone without one.
 */
@RunWith(RobolectricTestRunner::class)
class LoadHomeTest {

    private lateinit var db: AppDatabase
    private lateinit var vm: AppStoreViewModel

    @BeforeTest
    fun setUp() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        db = Room.inMemoryDatabaseBuilder(app, AppDatabase::class.java)
            .setDriver(BundledSQLiteDriver())
            .allowMainThreadQueries()
            .build()
        runBlocking {
            db.cachedAppDao().upsertAll(
                listOf(
                    UnifiedApp(
                        packageName = "com.example.modern",
                        source = AppSource.MODERN_APPS,
                        name = "Modern",
                        repoUrl = DefaultRepos.MODERN_APPS.url,
                    ).toEntity().copy(lastUpdated = 300L),
                    UnifiedApp(
                        packageName = "com.example.fdroid",
                        source = AppSource.FDROID,
                        name = "Fdroid",
                        repoUrl = DefaultRepos.FDROID.url,
                    ).toEntity().copy(lastUpdated = 200L),
                ),
            )
        }
        // Not closed: the ViewModel scope keeps collecting DB flows; the
        // in-memory DB dies with the Robolectric sandbox.
        vm = AppStoreViewModel(app, db)
    }

    private fun await(description: String, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 15_000
        while (System.currentTimeMillis() < deadline) {
            Shadows.shadowOf(Looper.getMainLooper())
                .idleFor(200, java.util.concurrent.TimeUnit.MILLISECONDS)
            if (condition()) return
            Thread.sleep(50)
        }
        throw AssertionError("timed out waiting for: $description; home=${vm.home.value}")
    }

    @Test
    fun `loadHome fills a recent row per enabled source`() = runBlocking {
        vm.loadHome(setOf(AppSource.MODERN_APPS, AppSource.FDROID))
        await("home rows") {
            vm.home.value.sections.map { it.id }.containsAll(listOf("modern-recent", "fdroid-recent"))
        }
        assertEquals(
            listOf("com.example.fdroid", "com.example.modern"),
            vm.recentlyUpdatedFlow.value.map { it.packageName }.sorted(),
        )
    }

    @Test
    fun `loadHome with nothing enabled clears the recent rows`() = runBlocking {
        vm.loadHome(setOf(AppSource.MODERN_APPS))
        await("modern row") {
            vm.home.value.sections.any { it.id == "modern-recent" }
        }
        vm.loadHome(emptySet())
        await("rows cleared") {
            vm.recentlyUpdatedFlow.value.isEmpty() &&
                vm.home.value.sections.none { it.id == "modern-recent" }
        }
        assertTrue(vm.modernRecentFlow.value.isEmpty())
    }
}
