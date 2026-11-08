package com.vayunmathur.appstore.tv

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
 * loadHome against a seeded catalogue: one row per enabled offline
 * source, disabled sources omitted, and a chosen category replacing
 * the browsing rows. Play/Accrescent stay disabled here — their rows
 * need a network this test must not touch.
 */
@RunWith(RobolectricTestRunner::class)
class TvStoreLoadHomeTest {

    private lateinit var db: AppDatabase
    private lateinit var vm: TvStoreViewModel

    private fun seed(pkg: String, source: AppSource, repo: String, updated: Long) {
        runBlocking {
            db.cachedAppDao().upsert(
                UnifiedApp(
                    packageName = pkg,
                    source = source,
                    name = pkg,
                    categories = listOf("Games"),
                    repoUrl = repo,
                ).toEntity().copy(lastUpdated = updated),
            )
        }
    }

    @BeforeTest
    fun setUp() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        db = Room.inMemoryDatabaseBuilder(app, AppDatabase::class.java)
            .setDriver(BundledSQLiteDriver())
            .allowMainThreadQueries()
            .build()
        seed("com.example.modern", AppSource.MODERN_APPS, DefaultRepos.MODERN_APPS.url, 300L)
        seed("com.example.fdroid", AppSource.FDROID, DefaultRepos.FDROID.url, 200L)
        seed("com.example.prop", AppSource.PROPRIETARY, DefaultRepos.PROPRIETARY.url, 100L)
        // Not closed: the ViewModel scope keeps collecting DB flows (see
        // TvStoreSourcesScreenTest); the sandbox owns the in-memory DB.
        vm = TvStoreViewModel(app, db)
    }

    private fun await(description: String, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 15_000
        while (System.currentTimeMillis() < deadline) {
            Shadows.shadowOf(Looper.getMainLooper())
                .idleFor(200, java.util.concurrent.TimeUnit.MILLISECONDS)
            if (condition()) return
            Thread.sleep(50)
        }
        throw AssertionError("timed out waiting for: $description; enabled=${vm.enabledSourcesFlow.value}; home=${vm.home.value}")
    }

    @Test
    fun `loadHome builds one row per enabled offline source`() {
        // Let the init pass settle first: it re-reads the enabled sources
        // from settings (F-Droid starts off, so its seeded row is purged
        // by that pass) and would overwrite the set below.
        await("init home") { vm.home.value.sections.isNotEmpty() }
        seed("com.example.fdroid", AppSource.FDROID, DefaultRepos.FDROID.url, 200L)
        vm.enabledSourcesFlow.value = setOf(
            AppSource.MODERN_APPS, AppSource.FDROID, AppSource.PROPRIETARY,
        )
        vm.loadHome()
        await("home rows") {
            vm.home.value.sections.map { it.id }.containsAll(
                listOf("modern-recent", "fdroid-recent", "proprietary-recent"),
            )
        }
        val byId = vm.home.value.sections.associateBy { it.id }
        assertEquals(
            listOf("com.example.modern"),
            byId.getValue("modern-recent").apps.map { it.packageName },
        )
        assertEquals(AppSource.FDROID, byId.getValue("fdroid-recent").source)
        assertTrue(vm.home.value.categories.contains("Games"))
    }

    @Test
    fun `loadHome with a category replaces the rows with that category`() {
        await("init home") { vm.home.value.sections.isNotEmpty() }
        vm.selectedCategoryFlow.value = "Games"
        vm.loadHome()
        await("category row") {
            vm.home.value.sections.singleOrNull()?.id == "category"
        }
        assertEquals("Games", vm.home.value.sections.single().title)
        assertTrue(
            vm.home.value.sections.single().apps.isNotEmpty(),
            "category row should carry the seeded Games apps",
        )
    }
}
