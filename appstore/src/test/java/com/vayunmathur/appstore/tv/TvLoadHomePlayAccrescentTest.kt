package com.vayunmathur.appstore.tv

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
        val deadline = System.currentTimeMillis() + 30_000
        while (System.currentTimeMillis() < deadline) {
            Shadows.shadowOf(Looper.getMainLooper())
                .idleFor(200, java.util.concurrent.TimeUnit.MILLISECONDS)
            if (condition()) return
            Thread.sleep(50)
        }
        throw AssertionError("timed out waiting for: $description; home=${vm.home.value}")
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
