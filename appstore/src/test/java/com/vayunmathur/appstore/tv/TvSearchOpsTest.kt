package com.vayunmathur.appstore.tv
import com.vayunmathur.appstore.platform.TvStoreViewModel

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
 * The TV two-wave search with every network source switched off: the
 * on-disk catalogue answers after the debounce, a query with no hits
 * settles as searched-but-empty, and clearing the query resets state.
 */
@RunWith(RobolectricTestRunner::class)
class TvSearchOpsTest {

    private lateinit var db: AppDatabase
    private lateinit var vm: TvStoreViewModel

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
                        packageName = "com.example.alpha",
                        source = AppSource.MODERN_APPS,
                        name = "Alpha",
                        summary = "the alpha app",
                        repoUrl = DefaultRepos.MODERN_APPS.url,
                    ).toEntity(),
                    UnifiedApp(
                        packageName = "com.example.beta",
                        source = AppSource.MODERN_APPS,
                        name = "Beta",
                        repoUrl = DefaultRepos.MODERN_APPS.url,
                    ).toEntity(),
                ),
            )
        }
        // Not closed: the ViewModel scope keeps collecting DB flows; the
        // in-memory DB dies with the Robolectric sandbox.
        vm = TvStoreViewModel(app, db)
        vm.enabledSourcesFlow.value = emptySet()
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
        const val PUMP_STEP_MS = 400L
        const val PARK_MS = 50L
        const val MAX_PUMP_STEPS = 6_000
    }

    @Test
    fun `a query finds the catalogue hit after the debounce`() {
        vm.setSearch("alpha")
        await("results") {
            vm.search.value.results.map { it.packageName } == listOf("com.example.alpha")
        }
        assertTrue(vm.search.value.hasSearched)
        assertEquals("alpha", vm.search.value.query)
    }

    @Test
    fun `a query with no hits settles as searched and empty`() {
        vm.setSearch("zzzznothing")
        await("settled empty") { vm.search.value.hasSearched && vm.search.value.results.isEmpty() }
        assertTrue(vm.search.value.results.isEmpty())
    }

    @Test
    fun `clearing the query resets the search state`() {
        vm.setSearch("alpha")
        await("results") { vm.search.value.results.isNotEmpty() }
        vm.setSearch("")
        await("cleared") {
            vm.search.value.results.isEmpty() && !vm.search.value.hasSearched &&
                vm.search.value.query.isEmpty()
        }
    }
}
