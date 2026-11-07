package com.vayunmathur.appstore.util

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
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows

/**
 * The eligible half of update-all: with this store marked as the
 * installer of record for the test app (Robolectric's install-source
 * shadow), its catalogue update is bulk-eligible, the run attempts it,
 * and the failure (the row publishes no download) is collected into the
 * run's summary state instead of crashing the batch.
 */
@RunWith(RobolectricTestRunner::class)
class PhoneUpdateAllEligibleTest {

    private lateinit var db: AppDatabase
    private lateinit var vm: AppStoreViewModel
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
        vm = AppStoreViewModel(app, db)
    }

    private fun await(description: String, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 30_000
        while (System.currentTimeMillis() < deadline) {
            Shadows.shadowOf(Looper.getMainLooper())
                .idleFor(200, java.util.concurrent.TimeUnit.MILLISECONDS)
            if (condition()) return
            Thread.sleep(50)
        }
        throw AssertionError(
            "timed out waiting for: $description; partitioned=${vm.partitionedUpdates.value}",
        )
    }

    @Test
    fun `updateAll attempts the eligible update and settles`() {
        await("eligible update") {
            vm.partitionedUpdates.value.eligible.any { it.packageName == ownPackage }
        }
        assertTrue(vm.partitionedUpdates.value.foreign.none { it.packageName == ownPackage })

        vm.updateAll()
        await("eligible install attempted") {
            vm.installer.stages.value[ownPackage] is InstallStage.Failed
        }
        await("run settled") { vm.updateAllJob?.isActive != true }
        val stage = vm.installer.stages.value[ownPackage] as InstallStage.Failed
        assertTrue(stage.reason.isNotBlank())
        assertEquals(
            ownPackage,
            vm.partitionedUpdates.value.eligible.first().packageName,
        )
    }
}
