package com.vayunmathur.appstore.util
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
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows

/**
 * The phone live search: with the network sources switched off in
 * persisted settings (the search job re-reads them, so the gRPC/Play
 * halves are skipped), the debounced search answers from the on-disk
 * catalogue and settles.
 */
@RunWith(RobolectricTestRunner::class)
class PhoneSearchLiveTest {

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
        // Not closed: the ViewModel scope keeps collecting DB flows; the
        // in-memory DB dies with the Robolectric sandbox.
        vm = AppStoreViewModel(app, db)
        editSettings {
            vm.settings.setSourceEnabled(AppSource.ACCRESCENT, false)
            vm.settings.setSourceEnabled(AppSource.PLAYSTORE, false)
        }
    }

    // Pumped looper waits (see AccrescentVmOpsW5Test.enableAccrescent):
    // parking or sleep-polling this thread can wait forever on work
    // queued in the paused main looper under CI CPU contention.
    private fun editSettings(block: suspend () -> Unit) {
        val done = CompletableDeferred<Unit>()
        CoroutineScope(Dispatchers.IO).launch {
            try { block(); done.complete(Unit) } catch (e: Throwable) { done.completeExceptionally(e) }
        }
        pumpUntil("settings edit") { done.isCompleted }
        done.getCompletionExceptionOrNull()?.let { throw it }
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
     * seconds that CI contention can burn before any work runs, and
     * the failure is raised only after [MAX_PUMP_STEPS] pumps.
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
        assertTrue(vm.search.value.playError.isEmpty())
    }

    @Test
    fun `a query with no hits settles as searched and empty`() {
        vm.setSearch("zzzznothing")
        await("settled empty") {
            vm.search.value.hasSearched && vm.search.value.results.isEmpty()
        }
    }
}
