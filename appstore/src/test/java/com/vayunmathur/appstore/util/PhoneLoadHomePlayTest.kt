package com.vayunmathur.appstore.util

import android.app.Application
import androidx.room3.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.appstore.data.AppDatabase
import com.vayunmathur.appstore.data.AppSource
import com.vayunmathur.appstore.data.DefaultRepos
import com.vayunmathur.appstore.data.UnifiedApp
import com.vayunmathur.appstore.data.toEntity
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * loadHome with Play enabled: the offline rows still fill from Room,
 * and Play's clusters/top-chart (unanswerable offline — no account can
 * be dispensed) leave the Play sections empty rather than failing the
 * whole home load.
 */
@RunWith(RobolectricTestRunner::class)
class PhoneLoadHomePlayTest {

    private lateinit var vm: AppStoreViewModel

    @BeforeTest
    fun setUp() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val db = Room.inMemoryDatabaseBuilder(app, AppDatabase::class.java)
            .setDriver(BundledSQLiteDriver())
            .allowMainThreadQueries()
            .build()
        runBlocking {
            db.cachedAppDao().upsert(
                UnifiedApp(
                    packageName = "com.example.modern",
                    source = AppSource.MODERN_APPS,
                    name = "Modern One",
                    repoUrl = DefaultRepos.MODERN_APPS.url,
                    lastUpdated = 100L,
                ).toEntity(),
            )
        }
        vm = AppStoreViewModel(app, db)
    }

    @Test
    fun `loadHome with play enabled fills offline rows and leaves play empty`() = runBlocking {
        vm.loadHome(setOf(AppSource.MODERN_APPS, AppSource.PLAYSTORE))
        assertTrue(
            vm.modernRecentFlow.value.any { it.packageName == "com.example.modern" },
            "modern recent was ${vm.modernRecentFlow.value}",
        )
        assertTrue(vm.playSectionsFlow.value.isEmpty())
        assertTrue(vm.playFullClustersFlow.value.isEmpty())
        assertEquals(false, vm.isLoadingHomeFlow.value)
    }

    @Test
    fun `loadHome with only play enabled clears the offline rows`() = runBlocking {
        vm.loadHome(setOf(AppSource.PLAYSTORE))
        assertTrue(vm.modernRecentFlow.value.isEmpty())
        assertTrue(vm.recentlyUpdatedFlow.value.isEmpty())
        assertEquals(false, vm.isLoadingHomeFlow.value)
    }
}
