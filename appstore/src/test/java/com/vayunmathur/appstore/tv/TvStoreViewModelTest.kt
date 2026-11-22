package com.vayunmathur.appstore.tv
import com.vayunmathur.appstore.platform.SourceFilter
import com.vayunmathur.appstore.platform.TvSection
import com.vayunmathur.appstore.platform.TvStoreViewModel
import com.vayunmathur.appstore.platform.TvStoreViewModelFactory
import com.vayunmathur.appstore.platform.loadBrowse
import com.vayunmathur.appstore.platform.loadDetail

import android.app.Application
import android.os.Looper
import androidx.room3.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.appstore.data.AppDatabase
import com.vayunmathur.appstore.data.AppSource
import com.vayunmathur.appstore.data.UnifiedApp
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows

/**
 * TV ViewModel state derivation, mirroring the phone ViewModel test:
 * raw flows in, per-screen TV state out, no network required.
 */
@RunWith(RobolectricTestRunner::class)
class TvStoreViewModelTest {

    private lateinit var db: AppDatabase
    private lateinit var vm: TvStoreViewModel

    private fun app(pkg: String, source: AppSource = AppSource.FDROID, name: String = pkg) =
        UnifiedApp(packageName = pkg, source = source, name = name, versionCode = 2L)

    @BeforeTest
    fun setUp() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        db = Room.inMemoryDatabaseBuilder(app, AppDatabase::class.java)
            .setDriver(BundledSQLiteDriver())
            .allowMainThreadQueries()
            .build()
        vm = TvStoreViewModel(app, db)
    }

    // Not closed: the ViewModel's background coroutines keep collecting
    // DB flows after a test, and closing the bundled-SQLite DB under them
    // crashed the test JVM natively (SIGSEGV in sqlite3DbMallocRawNN).
    // The in-memory DB dies with the Robolectric sandbox.

    private fun await(description: String, condition: () -> Boolean) {
        try {
            pumpUntil(description, condition)
        } catch (e: AssertionError) {
            throw AssertionError("timed out waiting for: $description; home=${vm.home.value}", e)
        }
    }

    /**
     * Step-bounded pumped wait on a terminal [condition]: every step
     * advances the paused main looper (running the ViewModel/DataStore
     * work queued there, including delayed tasks), then parks briefly
     * so Dispatchers.IO/Default producers get a CPU slice on a
     * contended box. The bound is pumping steps, never wall-clock
     * seconds that CI contention can burn before any work runs (the
     * previous 10-30s System.currentTimeMillis deadlines fired early
     * under a 5-CPU cap while the terminal state was still landing).
     */
    private fun pumpUntil(description: String, condition: () -> Boolean) {
        var steps = 0
        while (true) {
            // Pump first, then check: the loops this replaces always
            // idled at least once before judging the condition, and a
            // condition that is already true at entry can still need
            // that one pump for the work behind it to settle.
// Original pumping primitive for this class: idle() only,
            // no looper-clock advance (advancing it here drove delayed
            // work early and broke the terminal states this waits on).
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            if (condition()) break
            java.util.concurrent.locks.LockSupport.parkNanos(
                java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(PARK_MS),
            )
            if (++steps >= MAX_PUMP_STEPS) {
                throw AssertionError("timed out (step-bounded): $description after $MAX_PUMP_STEPS pumps")
            }
        }
    }

    private companion object {
        const val PUMP_STEP_MS = 100L
        const val PARK_MS = 50L
        const val MAX_PUMP_STEPS = 6_000
    }

    @Test
    fun `factory creates the tv view model`() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val created = TvStoreViewModelFactory(app, db).create(TvStoreViewModel::class.java)
        assertEquals(app.packageName, created.context.packageName)
    }

    @Test
    fun `home follows sections and status flows`() {
        val section = TvSection(id = "recent", title = "Recently updated", apps = listOf(app("com.example.a", name = "Alpha")))
        vm.sectionsFlow.value = listOf(section)
        vm.statusMessageFlow.value = "Checking sources"
        await("home section") { vm.home.value.sections == listOf(section) }
        assertEquals("Checking sources", vm.home.value.statusMessage)
    }

    @Test
    fun `loadDetail by package drives the detail state, replacing the previous app`() {
        // Detail is loaded by the route's package argument: a second
        // argument replaces the first instead of rendering a stale app.
        vm.loadDetail("com.example.detail")
        await("detail loaded") { vm.detail.value.app?.packageName == "com.example.detail" }
        vm.loadDetail("com.example.other")
        await("detail replaced") { vm.detail.value.app?.packageName == "com.example.other" }
        assertEquals("com.example.other", vm.detail.value.app?.packageName)
    }

    @Test
    fun `browse loads the section the route argument names`() {
        val section = TvSection(
            id = "play-cluster",
            title = "Top apps",
            apps = listOf(app("com.example.b", name = "Beta"), app("com.example.a", name = "Alpha")),
        )
        vm.sectionsFlow.value = listOf(section)
        vm.loadBrowse("play-cluster")
        await("browse apps sorted") {
            vm.browse.value.apps.map { it.name } == listOf("Alpha", "Beta")
        }
        assertEquals("play-cluster", vm.browse.value.sectionId)
        assertEquals("Top apps", vm.browse.value.title)

        // A different argument loads its own (empty) section, not the last one.
        vm.loadBrowse("modern")
        await("browse replaced") {
            vm.browse.value.sectionId == "modern" && !vm.browse.value.isLoading
        }
        assertTrue(vm.browse.value.apps.none { it.name == "Alpha" })
    }

    @Test
    fun `search filter narrows the tv search state`() {
        vm.queryFlow.value = "alpha"
        vm.searchResultsFlow.value = listOf(
            app("com.example.f", AppSource.FDROID, "Alpha F"),
            app("com.example.m", AppSource.MODERN_APPS, "Alpha M"),
        )
        await("all results") { vm.search.value.results.size == 2 }
        vm.setSearchFilter(SourceFilter.MODERN_APPS)
        await("filtered") { vm.search.value.results.map { it.packageName } == listOf("com.example.m") }
    }

    @Test
    fun `category selection with no rows yields an empty category section`() {
        vm.selectCategory("Games")
        await("category section") {
            vm.home.value.sections.any { it.id == "category" && it.title == "Games" }
        }
        assertTrue(vm.home.value.sections.first { it.id == "category" }.apps.isEmpty())
        vm.selectCategory(null)
        await("category cleared") { vm.selectedCategoryFlow.value == null }
    }
}
