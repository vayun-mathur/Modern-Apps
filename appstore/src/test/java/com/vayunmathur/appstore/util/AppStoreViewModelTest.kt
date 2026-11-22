package com.vayunmathur.appstore.util
import com.vayunmathur.appstore.platform.AppStoreViewModel
import com.vayunmathur.appstore.platform.AppStoreViewModelFactory
import com.vayunmathur.appstore.platform.BrowseUiState
import com.vayunmathur.appstore.platform.SourceFilter

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
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows

/**
 * Phone ViewModel state derivation: raw flows in, per-screen state out.
 * The repositories are real (in-memory Room, Robolectric PackageManager);
 * background init work is allowed to settle via the main looper, and only
 * state that does not need a network is asserted.
 */
@RunWith(RobolectricTestRunner::class)
class AppStoreViewModelTest {

    private lateinit var db: AppDatabase
    private lateinit var vm: AppStoreViewModel

    private fun app(pkg: String, source: AppSource = AppSource.FDROID, name: String = pkg) =
        UnifiedApp(packageName = pkg, source = source, name = name, versionCode = 2L)

    @BeforeTest
    fun setUp() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        db = Room.inMemoryDatabaseBuilder(app, AppDatabase::class.java)
            .setDriver(BundledSQLiteDriver())
            .allowMainThreadQueries()
            .build()
        runBlocking {
            db.cachedAppDao().upsert(
                app("com.example.modern", AppSource.MODERN_APPS, "Modern One")
                    .toEntity().copy(repoUrl = DefaultRepos.MODERN_APPS.url),
            )
        }
        vm = AppStoreViewModel(app, db)
    }

    // Not closed: the ViewModel's background coroutines keep collecting
    // DB flows after a test, and closing the bundled-SQLite DB under them
    // crashed the test JVM natively (SIGSEGV in sqlite3DbMallocRawNN).
    // The in-memory DB dies with the Robolectric sandbox.

    private fun await(description: String, condition: () -> Boolean) {
        try {
            pumpUntil(description, condition)
        } catch (e: AssertionError) {
            throw AssertionError("timed out waiting for: $description; home=${vm.home.value} search=${vm.search.value}", e)
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
    fun `factory creates the view model`() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val created = AppStoreViewModelFactory(app, db).create(AppStoreViewModel::class.java)
        assertEquals(app.packageName, created.context.packageName)
    }

    @Test
    fun `home reflects status and syncing flows`() {
        vm.statusMessageFlow.value = "Syncing catalogue"
        vm.isSyncingFlow.value = true
        await("status line") { vm.home.value.statusMessage == "Syncing catalogue" }
        assertTrue(vm.home.value.isSyncing)
    }

    @Test
    fun `modern apps from the catalogue reach the home state`() {
        await("modern section") {
            vm.home.value.sections.flatMap { it.apps }.any { it.packageName == "com.example.modern" }
        }
    }

    @Test
    fun `search state applies the source filter`() {
        vm.queryFlow.value = "alpha"
        vm.searchResultsFlow.value = listOf(
            app("com.example.fdroid", AppSource.FDROID, "Alpha F"),
            app("com.example.modern", AppSource.MODERN_APPS, "Alpha M"),
        )
        await("unfiltered results") { vm.search.value.results.size == 2 }
        vm.setSearchFilter(SourceFilter.FDROID)
        await("filtered results") {
            vm.search.value.results.map { it.packageName } == listOf("com.example.fdroid")
        }
        assertEquals(SourceFilter.FDROID, vm.search.value.filter)
        assertEquals("alpha", vm.search.value.query)
    }

    @Test
    fun `detail and browse states follow their flows`() {
        val selected = app("com.example.detail", name = "Detail App")
        vm.detailAppFlow.value = selected
        vm.isLoadingDetailsFlow.value = true
        await("detail") { vm.detail.value.app?.packageName == "com.example.detail" }
        assertTrue(vm.detail.value.isLoadingDetails)

        vm.browseContentFlow.value = BrowseUiState(
            sectionId = "games",
            title = "Games",
            apps = listOf(selected),
            isLoading = false,
        )
        await("browse") { vm.browse.value.title == "Games" }
        assertEquals("games", vm.browse.value.sectionId)
        assertEquals(listOf(selected), vm.browse.value.apps)
    }

    @Test
    fun `selecting and clearing a category updates home state`() {
        vm.selectCategory("Games")
        await("category selected") { vm.home.value.selectedCategory == "Games" }
        vm.selectCategory(null)
        await("category cleared") { vm.home.value.selectedCategory == null }
    }

    @Test
    fun `updates ui starts idle with no pending updates`() {
        await("updates state") { !vm.updatesUi.value.isChecking }
        assertTrue(vm.updatesUi.value.updates.isEmpty())
        assertFalse(vm.autoInstallUpdates.value)
        assertTrue(vm.enabledSources.value.isNotEmpty())
    }
}
