package com.vayunmathur.appstore.tv
import com.vayunmathur.appstore.platform.TvStoreViewModel
import com.vayunmathur.appstore.platform.merge

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
 * The TV search with Play and Accrescent enabled: the local catalogue
 * hit still leads, the network halves (unanswerable offline) merge in
 * as empty without erasing it, and no Play error is recorded.
 */
@RunWith(RobolectricTestRunner::class)
class TvSearchLiveSourcesTest {

    private lateinit var vm: TvStoreViewModel

    @BeforeTest
    fun setUp() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val db = Room.inMemoryDatabaseBuilder(app, AppDatabase::class.java)
            .setDriver(BundledSQLiteDriver())
            .allowMainThreadQueries()
            .build()
        runBlocking {
            db.cachedAppDao().upsert(
                UnifiedApp(
                    packageName = "com.example.alpha",
                    source = AppSource.MODERN_APPS,
                    name = "Alpha",
                    summary = "the alpha app",
                    repoUrl = DefaultRepos.MODERN_APPS.url,
                ).toEntity(),
            )
        }
        vm = TvStoreViewModel(app, db)
        vm.enabledSourcesFlow.value = setOf(
            AppSource.MODERN_APPS, AppSource.PLAYSTORE, AppSource.ACCRESCENT,
        )
    }

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
    fun `live sources enabled still surface the catalogue hit`() {
        vm.setSearch("alpha")
        await("searched") { vm.search.value.hasSearched }
        assertEquals(
            listOf("com.example.alpha"),
            vm.search.value.results.map { it.packageName },
        )
        assertTrue(vm.search.value.playError.isEmpty(), vm.search.value.playError)
    }
}
