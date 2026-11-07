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
import com.vayunmathur.appstore.data.toEntity
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows

/**
 * The Play half of a manual update check, with Play switched on in
 * persisted settings: Play cannot dispense a session offline, so the
 * lookup answers null and the previous Play rows are kept (here:
 * none) instead of being wiped — while the catalogue half still
 * answers and the check settles.
 */
@RunWith(RobolectricTestRunner::class)
class UpdateCheckPlayBranchTest {

    private lateinit var db: AppDatabase
    private lateinit var vm: AppStoreViewModel
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
        // Not closed: the ViewModel scope keeps collecting DB flows; the
        // in-memory DB dies with the Robolectric sandbox.
        vm = AppStoreViewModel(app, db)
        runBlocking {
            vm.settings.setSourceEnabled(AppSource.PLAYSTORE, true)
            vm.settings.setSourceEnabled(AppSource.ACCRESCENT, false)
        }
    }

    private fun await(description: String, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 30_000
        while (System.currentTimeMillis() < deadline) {
            Shadows.shadowOf(Looper.getMainLooper())
                .idleFor(400, java.util.concurrent.TimeUnit.MILLISECONDS)
            if (condition()) return
            Thread.sleep(50)
        }
        throw AssertionError(
            "timed out waiting for: $description; checking=${vm.isCheckingUpdatesFlow.value}; status=${vm.statusMessageFlow.value}; catalog=${vm.catalogUpdatesFlow.value}; updates=${vm.updatesUi.value}",
        )
    }

    @Test
    fun `check with play enabled keeps play rows when play cannot answer`() {
        vm.checkForUpdates()
        await("check settled") {
            !vm.updatesUi.value.isChecking && vm.updatesUi.value.lastCheckedAt > 0L
        }
        assertTrue(vm.playUpdatesFlow.value.isEmpty())
        val all = vm.updatesUi.value.updates + vm.updatesUi.value.foreignUpdates
        assertTrue(
            all.any { it.packageName == ownPackage },
            "catalogue update missing from $all",
        )
    }
}
