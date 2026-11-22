package com.vayunmathur.appstore.tv
import com.vayunmathur.appstore.platform.TvSection
import com.vayunmathur.appstore.platform.TvStoreViewModel

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
 * TV loadHome network branches with those sources enabled: Play
 * cannot dispense a session offline, so no Play rows appear and the
 * offline screen still settles; Accrescent rows come from the cached
 * listings flow. Disabling Play afterwards forgets its contributions.
 */
@RunWith(RobolectricTestRunner::class)
class TvHomeBranchesTest {

    private lateinit var db: AppDatabase
    private lateinit var vm: TvStoreViewModel

    @BeforeTest
    fun setUp() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        db = Room.inMemoryDatabaseBuilder(app, AppDatabase::class.java)
            .setDriver(BundledSQLiteDriver())
            .allowMainThreadQueries()
            .build()
        // Not closed: the ViewModel scope keeps collecting DB flows; the
        // in-memory DB dies with the Robolectric sandbox.
        vm = TvStoreViewModel(app, db)
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
        const val PUMP_STEP_MS = 400L
        const val PARK_MS = 50L
        const val MAX_PUMP_STEPS = 6_000
    }

    @Test
    fun `loadHome with play enabled offline settles with no play rows`() {
        vm.enabledSourcesFlow.value = setOf(AppSource.PLAYSTORE)
        vm.loadHome()
        await("home settled") { !vm.home.value.isLoading }
        assertTrue(
            vm.home.value.sections.none { it.source == AppSource.PLAYSTORE },
            "play sections appeared offline: ${vm.home.value.sections.map { it.id }}",
        )
        assertTrue(vm.playFullClustersFlow.value.isEmpty())
    }

    @Test
    fun `accrescent listings flow becomes a home row`() {
        vm.accrescentAppsFlow.value = listOf(
            UnifiedApp(
                packageName = "app.accrescent.example",
                source = AppSource.ACCRESCENT,
                name = "Accrescent App",
            ),
        )
        vm.enabledSourcesFlow.value = setOf(AppSource.ACCRESCENT)
        vm.loadHome()
        await("accrescent row") {
            vm.home.value.sections.any { it.id == "accrescent" }
        }
        assertEquals(
            listOf("app.accrescent.example"),
            vm.home.value.sections.first { it.id == "accrescent" }.apps.map { it.packageName },
        )
    }

    @Test
    fun `disabling play forgets its rows and clusters`() {
        vm.sectionsFlow.value = listOf(
            TvSection(
                id = "play-top",
                title = "Play",
                source = AppSource.PLAYSTORE,
                apps = listOf(
                    UnifiedApp("com.example.play", AppSource.PLAYSTORE, "Play App"),
                ),
            ),
            TvSection(id = "modern-recent", title = "Modern", source = AppSource.MODERN_APPS),
        )
        vm.playFullClustersFlow.value = mapOf("play-top" to emptyList())
        vm.setSourceEnabled(AppSource.PLAYSTORE, false)
        await("play forgotten") {
            vm.sectionsFlow.value.none { it.source == AppSource.PLAYSTORE } &&
                vm.playFullClustersFlow.value.isEmpty() &&
                AppSource.PLAYSTORE !in vm.enabledSourcesFlow.value
        }
        assertTrue(vm.playFullClustersFlow.value.isEmpty())
        // loadHome then rebuilds from the (empty) catalogue, so the
        // seeded modern row is gone too — what matters is Play is.
    }

    @Test
    fun `setAutoInstallUpdates persists through the view model`() {
        vm.setAutoInstallUpdates(true)
        await("auto install on") { vm.autoInstallUpdates.value }
        vm.setAutoInstallUpdates(false)
        await("auto install off") { !vm.autoInstallUpdates.value }
    }
}
