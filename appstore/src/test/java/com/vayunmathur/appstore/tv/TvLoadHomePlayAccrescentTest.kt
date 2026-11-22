package com.vayunmathur.appstore.tv
import com.vayunmathur.appstore.data.FDroidRepository
import com.vayunmathur.appstore.data.SettingsRepository
import com.vayunmathur.appstore.platform.TvStoreViewModel

import android.app.Application
import android.os.Looper
import androidx.room3.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.appstore.data.AppDatabase
import com.vayunmathur.appstore.data.AppSource
import com.vayunmathur.appstore.data.UnifiedApp
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertTrue
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows

/**
 * TV loadHome with Play and Accrescent enabled: the Accrescent row
 * comes from the cached listings flow, and Play (unanswerable offline)
 * contributes no cluster or top-chart row, without blocking the rest.
 */
@RunWith(RobolectricTestRunner::class)
class TvLoadHomePlayAccrescentTest {

    private lateinit var vm: TvStoreViewModel

    @BeforeTest
    fun setUp() {
        // Hermetic offline: catalogue downloads fail, so the ViewModel's
        // first-run sync fails closed instead of importing the real
        // catalogue, whatever network the test host happens to have.
        com.vayunmathur.appstore.data.FDroidRepository.downloadOverride = { url, _ ->
            throw java.io.IOException("offline test double for $url")
        }
        val app = ApplicationProvider.getApplicationContext<Application>()
        // The ViewModel's init reloads the enabled set from settings and
        // would overwrite the flow set below (a race that made this test
        // depend on coroutine timing): persist the same set first.
        kotlinx.coroutines.runBlocking {
            val settings = com.vayunmathur.appstore.data.SettingsRepository(
                app, kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO),
            )
            settings.setSourceEnabled(AppSource.PLAYSTORE, true)
            settings.setSourceEnabled(AppSource.ACCRESCENT, true)
            settings.setSourceEnabled(AppSource.MODERN_APPS, true)
            settings.setSourceEnabled(AppSource.FDROID, false)
            settings.setSourceEnabled(AppSource.IZZY, false)
            settings.setSourceEnabled(AppSource.PROPRIETARY, false)
        }
        val db = Room.inMemoryDatabaseBuilder(app, AppDatabase::class.java)
            .setDriver(BundledSQLiteDriver())
            .allowMainThreadQueries()
            .build()
        // Hermetic offline: Play dispensing fails and the Accrescent API
        // is a dead channel, whatever network the test host has.
        val offlinePlay = com.vayunmathur.appstore.data.play.PlayRepository(
            app,
            object : com.vayunmathur.appstore.data.play.AnonymousAuthRepository() {
                override suspend fun ensureAuthData(
                    deviceProps: java.util.Properties,
                    dispenserUrls: List<String>,
                ): Result<com.aurora.gplayapi.data.models.AuthData> =
                    Result.failure(java.io.IOException("offline test double"))
            },
        )
        val deadChannel = io.grpc.inprocess.InProcessChannelBuilder
            .forName("tv-loadhome-offline").directExecutor().build()
        val failingFetcher = object :
            com.vayunmathur.appstore.data.accrescent.AccrescentRepoDataFetcher(app) {
            override suspend fun fetch():
                Result<com.vayunmathur.appstore.data.accrescent.RepoData> =
                Result.failure(java.io.IOException("offline test double"))
        }
        vm = TvStoreViewModel(
            app, db,
            playOverride = offlinePlay,
            accrescentOverride = com.vayunmathur.appstore.data.accrescent.AccrescentRepository(
                app, db,
                com.vayunmathur.appstore.data.accrescent.AccrescentApi { deadChannel },
                failingFetcher,
            ),
        )
        vm.enabledSourcesFlow.value = setOf(
            AppSource.PLAYSTORE, AppSource.ACCRESCENT, AppSource.MODERN_APPS,
        )
        vm.accrescentAppsFlow.value = listOf(
            UnifiedApp("com.example.acc", AppSource.ACCRESCENT, "Acc App"),
        )
    }

    @AfterTest
    fun tearDown() {
        com.vayunmathur.appstore.data.FDroidRepository.downloadOverride = null
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
    fun `loadHome adds the accrescent row and no play row offline`() {
        vm.loadHome()
        await("accrescent section") {
            vm.home.value.sections.any { it.id == "accrescent" }
        }
        val sections = vm.home.value.sections
        assertTrue(sections.none { it.source == AppSource.PLAYSTORE }, sections.toString())
        assertTrue(
            sections.first { it.id == "accrescent" }.apps.map { it.packageName } ==
                listOf("com.example.acc"),
        )
    }
}
