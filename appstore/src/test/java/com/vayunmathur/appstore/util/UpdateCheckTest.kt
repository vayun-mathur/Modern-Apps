package com.vayunmathur.appstore.util

import android.app.Application
import android.os.Looper
import androidx.room3.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.appstore.data.AppDatabase
import com.vayunmathur.appstore.data.AppSource
import com.vayunmathur.appstore.data.UnifiedApp
import com.vayunmathur.appstore.data.toEntity
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows

/**
 * The manual update check: the catalogue half answers from Room (this
 * test app itself, offered a much newer catalogue version), the Play
 * and Accrescent halves fail soft offline, and the check always
 * settles — it must never wedge the updates tab in "checking".
 */
@RunWith(RobolectricTestRunner::class)
class UpdateCheckTest {

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
                ).toEntity(),
            )
        }
        // Not closed: the ViewModel scope keeps collecting DB flows; the
        // in-memory DB dies with the Robolectric sandbox.
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
            "timed out waiting for: $description; updates=${vm.updatesUi.value}",
        )
    }

    @Test
    fun `checkForUpdates surfaces the catalogue update and settles`() {
        // The init pass can purge/sync against the seeded row; re-seed once
        // it has settled so the check reads the catalogue this test wrote.
        await("init settled") { vm.home.value.sections.isNotEmpty() || !vm.isLoadingHomeFlow.value }
        runBlocking {
            db.cachedAppDao().upsert(
                UnifiedApp(
                    packageName = ownPackage,
                    source = AppSource.MODERN_APPS,
                    name = "This Store",
                    versionCode = 99_999_999L,
                ).toEntity(),
            )
        }
        vm.checkForUpdates()
        await("check settled") {
            !vm.updatesUi.value.isChecking && vm.updatesUi.value.lastCheckedAt > 0L
        }
        // The test app was not installed by this store, so its update is
        // listed as foreign (shown, never bulk-run) rather than eligible.
        val all = vm.updatesUi.value.updates + vm.updatesUi.value.foreignUpdates
        assertTrue(
            all.any { it.packageName == ownPackage },
            "own update missing from ${all.map { it.packageName }}; catalog=${vm.catalogUpdatesFlow.value.map { it.packageName to it.versionCode }}",
        )
        assertEquals(
            99_999_999L,
            all.first { it.packageName == ownPackage }.versionCode,
        )
    }

    @Test
    fun `a second check while one is running does not wedge the tab`() {
        vm.checkForUpdates()
        vm.checkForUpdates()
        await("check settled") {
            !vm.updatesUi.value.isChecking && vm.updatesUi.value.lastCheckedAt > 0L
        }
        assertFalse(vm.updatesUi.value.isChecking)
    }
}
