package com.vayunmathur.appstore.tv

import android.app.Application
import android.os.Looper
import androidx.room3.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.appstore.data.AccrescentTrustEntity
import com.vayunmathur.appstore.data.AppDatabase
import com.vayunmathur.appstore.data.AppSource
import com.vayunmathur.appstore.data.UnifiedApp
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows

/**
 * The TV update check with Play and Accrescent enabled: Play is asked
 * only about packages the catalogue does not list and keeps its
 * previous rows when it cannot answer (offline), Accrescent consults
 * its allowlist for installed packages, and the check settles with a
 * timestamp instead of wedging.
 */
@RunWith(RobolectricTestRunner::class)
class TvCheckBranchesTest {

    private lateinit var db: AppDatabase
    private lateinit var vm: TvStoreViewModel
    private lateinit var ownPackage: String

    @BeforeTest
    fun setUp() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        ownPackage = app.packageName
        db = Room.inMemoryDatabaseBuilder(app, AppDatabase::class.java)
            .setDriver(BundledSQLiteDriver())
            .allowMainThreadQueries()
            .build()
        runBlocking {
            db.accrescentTrustDao().upsertAll(
                listOf(AccrescentTrustEntity(ownPackage, "ab".repeat(32), 1L)),
            )
        }
        // Hermetic offline: Play dispensing fails (so previous Play rows
        // are kept) and the Accrescent refresh fails (so the seeded
        // allowlist survives), whatever network the test host has.
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
            .forName("tv-check-offline").directExecutor().build()
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
    }

    private fun await(description: String, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 30_000
        while (System.currentTimeMillis() < deadline) {
            Shadows.shadowOf(Looper.getMainLooper())
                .idleFor(200, java.util.concurrent.TimeUnit.MILLISECONDS)
            if (condition()) return
            Thread.sleep(50)
        }
        throw AssertionError("timed out waiting for: $description; updates=${vm.updatesUi.value}")
    }

    @Test
    fun `check with play and accrescent enabled settles and keeps previous play rows`() {
        val previous = UnifiedApp(ownPackage, AppSource.PLAYSTORE, "Previous Play Row")
        vm.playUpdatesFlow.value = listOf(previous)
        vm.checkForUpdates()
        await("check settled") {
            !vm.updatesUi.value.isChecking && vm.updatesUi.value.lastCheckedAt > 0L
        }
        // Play cannot answer offline: its previous row is kept, not wiped.
        assertTrue(
            vm.playUpdatesFlow.value.contains(previous),
            "play rows were ${vm.playUpdatesFlow.value}",
        )
        assertTrue(vm.accrescentUpdatesFlow.value.none { it.packageName == ownPackage })
    }
}
