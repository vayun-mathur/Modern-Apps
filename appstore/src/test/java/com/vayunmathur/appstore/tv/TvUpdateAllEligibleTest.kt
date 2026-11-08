package com.vayunmathur.appstore.tv

import android.app.Application
import android.os.Looper
import androidx.room3.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.appstore.data.AppDatabase
import com.vayunmathur.appstore.data.AppSource
import com.vayunmathur.appstore.data.DefaultRepos
import com.vayunmathur.appstore.data.UnifiedApp
import com.vayunmathur.appstore.data.installer.InstallStage
import com.vayunmathur.appstore.data.toEntity
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows

/**
 * TV update-all with an eligible update: this store is the installer of
 * record for the test app, the catalogue offers it a newer build, and
 * the batch attempts it — failing the row (no download published) and
 * settling rather than wedging.
 */
@RunWith(RobolectricTestRunner::class)
class TvUpdateAllEligibleTest {

    private lateinit var db: AppDatabase
    private lateinit var vm: TvStoreViewModel
    private lateinit var ownPackage: String

    @BeforeTest
    fun setUp() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        ownPackage = app.packageName
        Shadows.shadowOf(app.packageManager)
            .setInstallSourceInfo(ownPackage, ownPackage, ownPackage)
        db = Room.inMemoryDatabaseBuilder(app, AppDatabase::class.java)
            .setDriver(BundledSQLiteDriver())
            .allowMainThreadQueries()
            .build()
        runBlocking {
            db.cachedAppDao().upsert(
                UnifiedApp(
                    packageName = ownPackage,
                    source = AppSource.MODERN_APPS,
                    name = "This Store",
                    versionCode = 99_999_999L,
                    repoUrl = DefaultRepos.MODERN_APPS.url,
                ).toEntity(),
            )
        }
        vm = TvStoreViewModel(app, db)
        vm.enabledSourcesFlow.value = setOf(AppSource.MODERN_APPS)
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
    fun `updateAll attempts the eligible tv update and settles`() {
        vm.checkForUpdates()
        await("eligible update listed") {
            vm.updatesUi.value.updates.any { it.packageName == ownPackage }
        }
        vm.updateAll()
        await("install attempted") {
            vm.installer.stages.value[ownPackage] is InstallStage.Failed
        }
        await("run settled") { vm.updateAllJob?.isActive != true }
        assertTrue(vm.updatesUi.value.stages[ownPackage] is InstallStage.Failed)
    }
}
