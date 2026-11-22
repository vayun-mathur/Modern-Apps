package com.vayunmathur.appstore.util
import com.vayunmathur.appstore.platform.AppStoreViewModel

import android.app.Application
import android.os.Looper
import androidx.room3.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.appstore.data.AccrescentTrustEntity
import com.vayunmathur.appstore.data.AppDatabase
import com.vayunmathur.appstore.data.AppSource
import com.vayunmathur.appstore.data.SettingsRepository
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows

/**
 * The phone update check's Accrescent branch: with Accrescent enabled
 * and its signed allowlist vouching for an installed package, the check
 * asks for that package's update info (unreachable offline, so no
 * update materialises), records the attribution set, and settles.
 */
@RunWith(RobolectricTestRunner::class)
class PhoneCheckAccrescentTest {

    private lateinit var vm: AppStoreViewModel
    private lateinit var ownPackage: String

    @BeforeTest
    fun setUp() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        ownPackage = app.packageName
        val db = Room.inMemoryDatabaseBuilder(app, AppDatabase::class.java)
            .setDriver(BundledSQLiteDriver())
            .allowMainThreadQueries()
            .build()
        runBlocking {
            db.accrescentTrustDao().upsertAll(
                listOf(AccrescentTrustEntity(ownPackage, "ab".repeat(32), 1L)),
            )
            val settings = SettingsRepository(app, CoroutineScope(Dispatchers.IO))
            settings.setSourceEnabled(AppSource.ACCRESCENT, true)
            settings.setSourceEnabled(AppSource.PLAYSTORE, true)
        }
        // Hermetic offline: Play dispensing fails, the Accrescent API is
        // a dead channel and its repodata refresh fails, so the seeded
        // allowlist above survives — whatever network the test host has.
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
            .forName("phone-check-offline").directExecutor().build()
        val failingFetcher = object :
            com.vayunmathur.appstore.data.accrescent.AccrescentRepoDataFetcher(app) {
            override suspend fun fetch():
                Result<com.vayunmathur.appstore.data.accrescent.RepoData> =
                Result.failure(java.io.IOException("offline test double"))
        }
        vm = AppStoreViewModel(
            app, db,
            playOverride = offlinePlay,
            accrescentOverride = com.vayunmathur.appstore.data.accrescent.AccrescentRepository(
                app, db,
                com.vayunmathur.appstore.data.accrescent.AccrescentApi { deadChannel },
                failingFetcher,
            ),
        )
    }

    private fun await(description: String, condition: () -> Boolean) {
        try {
            pumpUntil(description, condition)
        } catch (e: AssertionError) {
            throw AssertionError("timed out waiting for: $description; updates=${vm.updatesUi.value}", e)
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
    fun `check with accrescent enabled consults the allowlist and settles`() {
        vm.checkForUpdates()
        await("check settled") {
            !vm.updatesUi.value.isChecking && vm.updatesUi.value.lastCheckedAt > 0L
        }
        assertTrue(
            ownPackage in vm.accrescentPackagesFlow.value,
            "attribution was ${vm.accrescentPackagesFlow.value}",
        )
        assertTrue(vm.accrescentUpdatesFlow.value.none { it.packageName == ownPackage })
    }
}
