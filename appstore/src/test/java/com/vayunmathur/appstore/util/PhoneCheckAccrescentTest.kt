package com.vayunmathur.appstore.util

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
