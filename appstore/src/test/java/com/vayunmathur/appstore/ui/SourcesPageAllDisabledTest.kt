package com.vayunmathur.appstore.ui

import android.app.Application
import android.os.Looper
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.room3.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.appstore.data.AppDatabase
import com.vayunmathur.appstore.data.AppSource
import com.vayunmathur.appstore.platform.AppStoreViewModel
import kotlin.test.BeforeTest
import kotlin.test.Test
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows

/**
 * With every toggleable source switched off the sources screen says
 * so plainly, instead of presenting six off switches with no hint
 * that the store now has nothing to browse from.
 */
@RunWith(RobolectricTestRunner::class)
class SourcesPageAllDisabledTest {

    @get:Rule
    val rule = createComposeRule()

    private lateinit var vm: AppStoreViewModel

    @BeforeTest
    fun setUp() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val db = Room.inMemoryDatabaseBuilder(app, AppDatabase::class.java)
            .setDriver(BundledSQLiteDriver())
            .allowMainThreadQueries()
            .build()
        vm = AppStoreViewModel(app, db)
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
    fun `switching every source off shows the nothing to browse warning`() {
        for (source in AppSource.TOGGLEABLE) vm.setSourceEnabled(source, false)
        await("all sources disabled") {
            AppSource.TOGGLEABLE.none { it in vm.enabledSources.value }
        }

        rule.setContent { SourcesPage(viewModel = vm) }

        rule.onNodeWithText("Every source is switched off", substring = true)
            .assertIsDisplayed()
    }
}
