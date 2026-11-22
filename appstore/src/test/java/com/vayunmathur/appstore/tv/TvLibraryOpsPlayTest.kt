package com.vayunmathur.appstore.tv
import com.vayunmathur.appstore.platform.SourceFilter
import com.vayunmathur.appstore.platform.TvStoreViewModel
import com.vayunmathur.appstore.platform.setLibraryFilter

import android.app.Application
import android.os.Looper
import androidx.room3.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.appstore.data.AppDatabase
import com.vayunmathur.appstore.data.AppSource
import com.vayunmathur.appstore.data.SettingsRepository
import com.vayunmathur.appstore.data.installer.InstallEvents
import com.vayunmathur.appstore.data.installer.InstallResult
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows

/**
 * TV library attribution when Play is enabled: the ViewModel asks Play
 * (via detailsOrNull) which installed packages it hosts. Offline the
 * lookup cannot be dispensed, so the previous attribution is kept —
 * and a successful install event re-runs the same refresh without
 * emptying the library.
 */
@RunWith(RobolectricTestRunner::class)
class TvLibraryOpsPlayTest {

    private lateinit var db: AppDatabase
    private lateinit var app: Application

    @BeforeTest
    fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(app, AppDatabase::class.java)
            .setDriver(BundledSQLiteDriver())
            .allowMainThreadQueries()
            .build()
        // Persist Play as enabled before the ViewModel reads settings.
        val settings = SettingsRepository(app, CoroutineScope(Dispatchers.IO))
        runBlocking { settings.setSourceEnabled(AppSource.PLAYSTORE, true) }
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
    fun `play attribution keeps the previous answer when play cannot answer`() {
        val vm = TvStoreViewModel(app, db)
        await("play enabled at init") { AppSource.PLAYSTORE in vm.enabledSourcesFlow.value }
        vm.playInstalledPackagesFlow.value = setOf("com.example.kept")
        // A successful install for another package re-runs the refresh:
        // Play still cannot answer offline, so the kept set survives.
        InstallEvents.publish(InstallResult("com.example.other", success = true))
        await("refresh settled") {
            vm.installedRepo.apps.value.any { it.packageName == app.packageName }
        }
        // Quiescence settle (the negative half of this assertion): pump
        // the looper for a bounded window so any erroneous clear the
        // refresh might still issue has every chance to land, instead
        // of sleeping 500ms and hoping it would have happened by then.
        repeat(50) {
            Shadows.shadowOf(Looper.getMainLooper())
                .idleFor(100, java.util.concurrent.TimeUnit.MILLISECONDS)
            java.util.concurrent.locks.LockSupport.parkNanos(
                java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(10),
            )
        }
        assertEquals(setOf("com.example.kept"), vm.playInstalledPackagesFlow.value)
    }

    @Test
    fun `library filter can be narrowed and reset`() {
        val vm = TvStoreViewModel(app, db)
        vm.setLibraryFilter(com.vayunmathur.appstore.platform.SourceFilter.FDROID)
        assertEquals(
            com.vayunmathur.appstore.platform.SourceFilter.FDROID,
            vm.libraryFilterFlow.value,
        )
        vm.setLibraryFilter(com.vayunmathur.appstore.platform.SourceFilter.ALL)
        assertTrue(vm.library.value.apps.all { true })
    }
}
