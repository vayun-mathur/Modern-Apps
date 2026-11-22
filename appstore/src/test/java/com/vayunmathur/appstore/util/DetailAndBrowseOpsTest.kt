package com.vayunmathur.appstore.util
import com.vayunmathur.appstore.platform.AppStoreViewModel
import com.vayunmathur.appstore.platform.loadBrowse
import com.vayunmathur.appstore.platform.loadDetail

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
 * Route-argument loading for Detail and Browse: `loadDetail` resolves a
 * package name against the catalogue (the catalogue row wins), Accrescent
 * and Play, shelling an unknown package; `loadBrowse` expands the section
 * a `Route.Browse` argument names to its full A–Z catalogue. This is what
 * the Detail/Browse entries call with their route arguments, so a restored
 * entry shows the app/section it names.
 */
@RunWith(RobolectricTestRunner::class)
class DetailAndBrowseOpsTest {

    private lateinit var db: AppDatabase
    private lateinit var vm: AppStoreViewModel

    @BeforeTest
    fun setUp() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        db = Room.inMemoryDatabaseBuilder(app, AppDatabase::class.java)
            .setDriver(BundledSQLiteDriver())
            .allowMainThreadQueries()
            .build()
        seedCatalogue()
        vm = AppStoreViewModel(app, db)
    }

    private fun seedCatalogue() = runBlocking {
        db.cachedAppDao().upsertAll(
            listOf(
                UnifiedApp(
                    packageName = "com.example.cached",
                    source = AppSource.FDROID,
                    name = "Cached Name",
                    versionCode = 9L,
                    categories = listOf("Games"),
                    repoUrl = DefaultRepos.FDROID.url,
                ).toEntity(),
                UnifiedApp(
                    packageName = "com.example.zmodern",
                    source = AppSource.MODERN_APPS,
                    name = "Zulu Modern",
                    repoUrl = DefaultRepos.MODERN_APPS.url,
                ).toEntity(),
                UnifiedApp(
                    packageName = "com.example.amodern",
                    source = AppSource.MODERN_APPS,
                    name = "Alpha Modern",
                    repoUrl = DefaultRepos.MODERN_APPS.url,
                ).toEntity(),
            ),
        )
    }

    /**
     * Settle-then-reseed (the LoadHomeTest arrangement): ViewModel init
     * purges every disabled source's cached rows — F-Droid is not in
     * DEFAULT_ENABLED, so the seeded "Cached Name" row is deleted
     * whenever init's purge lands before a test's query, and the pumped
     * awaits below then burn their whole 6,000-step budget on a
     * condition that can never become true again (observed: "category
     * browse" wedged for 300s in a contended companion run). The
     * modern-recent section is init's terminal home signal (only its
     * loadHome can produce it here); re-seeding afterwards restores
     * the row init legitimately purged, and no second purge runs.
     */
    private fun settleInit() {
        await("init home") {
            vm.home.value.sections.any { it.id == "modern-recent" }
        }
        seedCatalogue()
    }

    private fun await(description: String, condition: () -> Boolean) {
        try {
            pumpUntil(description, condition)
        } catch (e: AssertionError) {
            throw AssertionError("timed out waiting for: $description", e)
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
        const val PUMP_STEP_MS = 200L
        const val PARK_MS = 50L
        const val MAX_PUMP_STEPS = 6_000
    }

    @Test
    fun `loadDetail by package resolves the catalogue row`() {
        settleInit()
        // Even a package Play also lists: the catalogue row an install
        // would use wins over any shell listing.
        vm.loadDetail("com.example.cached")
        await("cached detail") { vm.detail.value.app?.name == "Cached Name" }
        assertEquals(9L, vm.detail.value.app?.versionCode)
        assertEquals(AppSource.FDROID, vm.detail.value.app?.source)
    }

    @Test
    fun `loadDetail shells an unknown package by its last segment`() {
        vm.loadDetail("com.example.unknown")
        await("shell by package") { vm.detail.value.app?.packageName == "com.example.unknown" }
        assertEquals("unknown", vm.detail.value.app?.name)
        assertEquals(AppSource.PLAYSTORE, vm.detail.value.app?.source)
    }

    @Test
    fun `loading a second package replaces the first detail`() {
        settleInit()
        // The route-argument contract in one test: the entry for package B
        // must never render package A's listing, not even briefly from a
        // stale flow — loadDetail drops the old app synchronously.
        vm.loadDetail("com.example.cached")
        await("first detail") { vm.detail.value.app?.packageName == "com.example.cached" }
        vm.loadDetail("com.example.zmodern")
        assertTrue(
            vm.detail.value.app?.packageName != "com.example.cached",
            "stale detail survived a new route argument: ${vm.detail.value}",
        )
        await("second detail") { vm.detail.value.app?.packageName == "com.example.zmodern" }
        assertEquals("Zulu Modern", vm.detail.value.app?.name)
    }

    @Test
    fun `browse expands an offline section to its full sorted catalogue`() {
        settleInit()
        vm.loadBrowse("modern-recent")
        await("browse loaded") { vm.browse.value.apps.size == 2 && !vm.browse.value.isLoading }
        assertEquals("modern-recent", vm.browse.value.sectionId)
        assertEquals(
            listOf("Alpha Modern", "Zulu Modern"),
            vm.browse.value.apps.map { it.name },
        )
        assertTrue(vm.browse.value.title.isNotBlank())
    }

    @Test
    fun `browse by category and by play cluster fallback`() {
        settleInit()
        // A category's name lives only in the home row's title: browse the
        // category home is currently showing.
        vm.selectCategory("Games")
        await("category section on home") {
            vm.home.value.sections.any { it.id == "category" && it.title == "Games" }
        }
        vm.loadBrowse("category")
        await("category browse") {
            vm.browse.value.apps.map { it.packageName } == listOf("com.example.cached")
        }

        val clusterApps = listOf(
            UnifiedApp("com.example.b", AppSource.PLAYSTORE, "Beta"),
            UnifiedApp("com.example.a", AppSource.PLAYSTORE, "Alpha"),
        )
        vm.playFullClustersFlow.value = mapOf("play-cluster" to clusterApps)
        vm.loadBrowse("play-cluster")
        await("play browse") {
            vm.browse.value.apps.map { it.name } == listOf("Alpha", "Beta")
        }
        assertEquals("play-cluster", vm.browse.value.sectionId)
        assertTrue(vm.browse.value.apps.all { it.source == AppSource.PLAYSTORE })
    }

    @Test
    fun `updateAll with nothing eligible is a no-op`() {
        vm.updateAll()
        await("settled") { vm.updateAllJob?.isActive != true }
    }
}
