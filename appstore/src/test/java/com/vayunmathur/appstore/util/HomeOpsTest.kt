package com.vayunmathur.appstore.util
import com.vayunmathur.appstore.data.priority
import com.vayunmathur.appstore.platform.AppSection
import com.vayunmathur.appstore.platform.AppStoreViewModel
import com.vayunmathur.appstore.platform.SectionLayout
import com.vayunmathur.appstore.platform.buildSections
import com.vayunmathur.appstore.platform.merge

import android.app.Application
import android.os.Looper
import androidx.room3.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.appstore.data.AppDatabase
import com.vayunmathur.appstore.data.AppSource
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
 * Home section construction and the debounced search: which rows the
 * home screen gets for each combination of catalogue state, and that a
 * typed query reaches the on-disk catalogue.
 */
@RunWith(RobolectricTestRunner::class)
class HomeOpsTest {

    private lateinit var db: AppDatabase
    private lateinit var vm: AppStoreViewModel

    private fun app(pkg: String, source: AppSource = AppSource.FDROID, name: String = pkg) =
        UnifiedApp(packageName = pkg, source = source, name = name, versionCode = 1L)

    @BeforeTest
    fun setUp() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        db = Room.inMemoryDatabaseBuilder(app, AppDatabase::class.java)
            .setDriver(BundledSQLiteDriver())
            .allowMainThreadQueries()
            .build()
        runBlocking {
            // MODERN_APPS is enabled by default; the search filters to enabled
            // sources, and F-Droid starts off.
            db.cachedAppDao().upsert(
                app("com.example.alpha", AppSource.MODERN_APPS, "Alpha").toEntity(),
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
            throw AssertionError("timed out waiting for: $description; search=${vm.search.value}", e)
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
            Shadows.shadowOf(Looper.getMainLooper())
                .idleFor(PUMP_STEP_MS, java.util.concurrent.TimeUnit.MILLISECONDS)
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
        const val PUMP_STEP_MS = 400L
        const val PARK_MS = 50L
        const val MAX_PUMP_STEPS = 6_000
    }

    @Test
    fun `empty catalogue builds no sections`() {
        assertEquals(
            emptyList(),
            vm.buildSections(
                modern = emptyList(), modernRecent = emptyList(), proprietaryRecent = emptyList(),
                fdroidRecent = emptyList(), izzyRecent = emptyList(), playSections = emptyList(),
                accrescent = emptyList(), categoryApps = emptyList(), category = null,
            ),
        )
    }

    @Test
    fun `a chosen category replaces all browsing rows`() {
        val sections = vm.buildSections(
            modern = listOf(app("com.example.m", AppSource.MODERN_APPS)),
            modernRecent = listOf(app("com.example.mr", AppSource.MODERN_APPS)),
            proprietaryRecent = listOf(app("com.example.p")),
            fdroidRecent = listOf(app("com.example.f")),
            izzyRecent = listOf(app("com.example.i", AppSource.IZZY)),
            playSections = listOf(AppSection("play", "Play", listOf(app("com.example.pl", AppSource.PLAYSTORE)))),
            accrescent = listOf(app("com.example.a", AppSource.ACCRESCENT)),
            categoryApps = listOf(app("com.example.cat")),
            category = "Games",
        )
        assertEquals(1, sections.size)
        assertEquals("category", sections.single().id)
        assertEquals("Games", sections.single().title)
        assertEquals(SectionLayout.LIST, sections.single().layout)
        assertEquals(listOf("com.example.cat"), sections.single().apps.map { it.packageName })
    }

    @Test
    fun `sections are ordered modern proprietary play accrescent fdroid izzy`() {
        val sections = vm.buildSections(
            modern = emptyList(),
            modernRecent = listOf(app("com.example.mr", AppSource.MODERN_APPS)),
            proprietaryRecent = listOf(app("com.example.p")),
            fdroidRecent = listOf(app("com.example.f")),
            izzyRecent = listOf(app("com.example.i", AppSource.IZZY)),
            playSections = listOf(AppSection("play-top", "Play", emptyList())),
            accrescent = listOf(app("com.example.a", AppSource.ACCRESCENT)),
            categoryApps = emptyList(),
            category = null,
        )
        assertEquals(
            listOf("modern-recent", "proprietary-recent", "play-top", "accrescent", "fdroid-recent", "izzy-recent"),
            sections.map { it.id },
        )
    }

    @Test
    fun `modern falls back to the full row when there is no recent row`() {
        val sections = vm.buildSections(
            modern = listOf(app("com.example.m", AppSource.MODERN_APPS)),
            modernRecent = emptyList(), proprietaryRecent = emptyList(),
            fdroidRecent = emptyList(), izzyRecent = emptyList(), playSections = emptyList(),
            accrescent = emptyList(), categoryApps = emptyList(), category = null,
        )
        assertEquals(listOf("modern"), sections.map { it.id })
    }

    @Test
    fun `merge keeps the highest priority source per package`() {
        val merged = vm.merge(
            listOf(app("com.example.x", AppSource.PLAYSTORE), app("com.example.y", AppSource.FDROID)),
            listOf(app("com.example.x", AppSource.MODERN_APPS)),
        )
        assertEquals(2, merged.size)
        assertEquals(AppSource.MODERN_APPS, merged.first { it.packageName == "com.example.x" }.source)
    }

    @Test
    fun `clearing the query clears the search state`() {
        // The live debounced search reads DataStore + Room on IO and does
        // not settle under Robolectric's paused main looper; the blank
        // branch is synchronous and is what a cleared field must do.
        vm.searchResultsFlow.value = listOf(app("com.example.alpha", name = "Alpha"))
        vm.hasSearchedFlow.value = true
        vm.setSearch("")
        await("cleared") {
            vm.search.value.results.isEmpty() && !vm.search.value.hasSearched &&
                vm.search.value.query.isEmpty()
        }
    }
}
