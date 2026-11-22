package com.vayunmathur.appstore.util
import com.vayunmathur.appstore.platform.loadHome
import com.vayunmathur.appstore.platform.loadAccrescent
import com.vayunmathur.appstore.platform.accrescentUpdate
import com.vayunmathur.appstore.platform.AppStoreViewModel

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

    private fun seedCatalogue() = runBlocking {
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

    @BeforeTest
    fun setUp() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        db = Room.inMemoryDatabaseBuilder(app, AppDatabase::class.java)
            .setDriver(BundledSQLiteDriver())
            .allowMainThreadQueries()
            .build()
        seedCatalogue()
        // Not closed: the ViewModel scope keeps collecting DB flows; the
        // in-memory DB dies with the Robolectric sandbox.
        vm = AppStoreViewModel(app, db)
    }

    /**
     * Settle-then-reseed wrapper for the test bodies.
     *
     * Init re-reads the enabled set from settings (a fresh install's
     * DEFAULT_ENABLED: MODERN_APPS + PROPRIETARY), purges every other
     * source's cached rows — including this fixture's F-Droid row — and
     * runs its own loadHome with that set. Racing the test's loadHome
     * against that pass made the outcome depend on which coroutine the
     * paused main looper happened to run first: when init landed last,
     * the F-Droid row was purged and the home state overwritten, and
     * the pumped waits below burned their whole step budget (~300s of
     * parking) on a condition that could never become true again.
     * The modern-recent section is init's terminal home signal (only
     * its loadHome can produce it here), and re-seeding afterwards
     * restores the row init legitimately purged — the
     * TvStoreLoadHomeTest arrangement.
     */
    private fun afterInitSettled(body: () -> Unit) {
        await("init home") {
            vm.home.value.sections.any { it.id == "modern-recent" }
        }
        seedCatalogue()
        body()
    }

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
    fun `loadHome fills a recent row per enabled source`() = afterInitSettled {
        runBlocking {
            vm.loadHome(setOf(AppSource.MODERN_APPS, AppSource.FDROID))
        }
        await("home rows") {
            vm.home.value.sections.map { it.id }.containsAll(listOf("modern-recent", "fdroid-recent"))
        }
        assertEquals(
            listOf("com.example.fdroid", "com.example.modern"),
            vm.recentlyUpdatedFlow.value.map { it.packageName }.sorted(),
        )
    }

    @Test
    fun `loadHome with nothing enabled clears the recent rows`() = afterInitSettled {
        runBlocking {
            vm.loadHome(setOf(AppSource.MODERN_APPS))
        }
        await("modern row") {
            vm.home.value.sections.any { it.id == "modern-recent" }
        }
        runBlocking {
            vm.loadHome(emptySet())
        }
        await("rows cleared") {
            vm.recentlyUpdatedFlow.value.isEmpty() &&
                vm.home.value.sections.none { it.id == "modern-recent" }
        }
        assertTrue(vm.modernRecentFlow.value.isEmpty())
    }
}
