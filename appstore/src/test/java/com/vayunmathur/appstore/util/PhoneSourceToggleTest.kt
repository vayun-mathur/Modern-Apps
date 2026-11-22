package com.vayunmathur.appstore.util
import com.vayunmathur.appstore.data.DefaultRepos
import com.vayunmathur.appstore.platform.AppSection
import com.vayunmathur.appstore.platform.AppStoreViewModel
import com.vayunmathur.appstore.platform.refreshInstalled

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
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows

/**
 * Phone ViewModel source toggles and installed refresh: turning a source
 * off drops what it contributed to the screens (Play sections, Accrescent
 * rows and updates) and turning it back on restores the enabled set,
 * while refreshInstalled re-reads the device into the library state.
 */
@RunWith(RobolectricTestRunner::class)
class PhoneSourceToggleTest {

    private lateinit var db: AppDatabase
    private lateinit var vm: AppStoreViewModel
    private lateinit var app: Application

    @BeforeTest
    fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(app, AppDatabase::class.java)
            .setDriver(BundledSQLiteDriver())
            .allowMainThreadQueries()
            .build()
        // The library only lists installed apps a source vouches for:
        // seed the catalogue row that attributes this test app.
        kotlinx.coroutines.runBlocking {
            db.cachedAppDao().upsert(
                UnifiedApp(
                    packageName = app.packageName,
                    source = AppSource.MODERN_APPS,
                    name = "This Store",
                ).toEntity()
                    .copy(repoUrl = com.vayunmathur.appstore.data.DefaultRepos.MODERN_APPS.url),
            )
        }
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
    fun `turning play off forgets its sections updates and installed set`() {
        vm.playSectionsFlow.value = listOf(
            AppSection("play-top", "Top", emptyList(), subtitle = "sub"),
        )
        vm.playUpdatesFlow.value = listOf(
            UnifiedApp("com.example.p", AppSource.PLAYSTORE, "P"),
        )
        vm.playInstalledPackagesFlow.value = setOf("com.example.p")
        vm.setSourceEnabled(AppSource.PLAYSTORE, false)
        await("play forgotten") {
            vm.playSectionsFlow.value.isEmpty() &&
                vm.playUpdatesFlow.value.isEmpty() &&
                vm.playInstalledPackagesFlow.value.isEmpty()
        }
        assertTrue(AppSource.PLAYSTORE !in vm.enabledSources.value)
    }

    @Test
    fun `turning accrescent off forgets its rows and updates`() {
        vm.accrescentAppsFlow.value = listOf(
            UnifiedApp("com.example.a", AppSource.ACCRESCENT, "A"),
        )
        vm.accrescentUpdatesFlow.value = listOf(
            UnifiedApp("com.example.a", AppSource.ACCRESCENT, "A"),
        )
        vm.accrescentPackagesFlow.value = setOf("com.example.a")
        vm.setSourceEnabled(AppSource.ACCRESCENT, false)
        await("accrescent forgotten") {
            vm.accrescentAppsFlow.value.isEmpty() &&
                vm.accrescentUpdatesFlow.value.isEmpty() &&
                vm.accrescentPackagesFlow.value.isEmpty()
        }
    }

    @Test
    fun `turning an offline source off and on settles in the enabled set`() {
        vm.setSourceEnabled(AppSource.FDROID, true)
        await("fdroid enabled") { AppSource.FDROID in vm.enabledSources.value }
        vm.setSourceEnabled(AppSource.FDROID, false)
        await("fdroid disabled") { AppSource.FDROID !in vm.enabledSources.value }
    }

    @Test
    fun `refreshInstalled republishes the device apps`() {
        vm.refreshInstalled()
        await("library contains this app") {
            vm.library.value.apps.any { it.packageName == app.packageName }
        }
        val mine = vm.library.value.apps.first { it.packageName == app.packageName }
        assertEquals(app.packageName, mine.packageName)
    }

    @Test
    fun `auto install toggle round trips through settings`() {
        vm.setAutoInstallUpdates(true)
        await("auto install on") { vm.autoInstallUpdates.value }
        vm.setAutoInstallUpdates(false)
        await("auto install off") { !vm.autoInstallUpdates.value }
    }
}
