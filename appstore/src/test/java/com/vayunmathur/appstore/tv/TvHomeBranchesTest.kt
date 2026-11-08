package com.vayunmathur.appstore.tv

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
        val deadline = System.currentTimeMillis() + 30_000
        while (System.currentTimeMillis() < deadline) {
            Shadows.shadowOf(Looper.getMainLooper())
                .idleFor(400, java.util.concurrent.TimeUnit.MILLISECONDS)
            if (condition()) return
            Thread.sleep(50)
        }
        throw AssertionError("timed out waiting for: $description; home=${vm.home.value}")
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
