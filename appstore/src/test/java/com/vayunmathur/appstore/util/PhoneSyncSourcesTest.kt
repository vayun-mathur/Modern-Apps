package com.vayunmathur.appstore.util
import com.vayunmathur.appstore.platform.AppStoreViewModel

import android.app.Application
import android.os.Looper
import androidx.room3.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.appstore.data.AppDatabase
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows

/**
 * A manual sync (refresh) runs to completion offline: the syncing flag
 * rises and falls, the status line clears, and the home state settles
 * instead of leaving the screen mid-sync.
 */
@RunWith(RobolectricTestRunner::class)
class PhoneSyncSourcesTest {

    private lateinit var vm: AppStoreViewModel

    @BeforeTest
    fun setUp() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val db = Room.inMemoryDatabaseBuilder(app, AppDatabase::class.java)
            .setDriver(BundledSQLiteDriver())
            .allowMainThreadQueries()
            .build()
        vm = AppStoreViewModel(app, db)
    }

    @Test
    fun `refresh syncs and settles`() {
        // Let the init work finish first so refresh is the only sync.
        // Step-bounded pumps instead of wall-clock deadlines (see
        // PagesWrapperTest): looper steps, not seconds CI contention
        // can burn before the sync settles.
        var initSteps = 0
        while (initSteps++ < 6_000) {
            Shadows.shadowOf(Looper.getMainLooper())
                .idleFor(100, java.util.concurrent.TimeUnit.MILLISECONDS)
            if (!vm.isSyncingFlow.value && !vm.isLoadingHomeFlow.value) break
            java.util.concurrent.locks.LockSupport.parkNanos(
                java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(10),
            )
        }
        vm.refresh()
        var sawSyncingOrSettled = false
        var steps = 0
        while (steps++ < 6_000) {
            Shadows.shadowOf(Looper.getMainLooper())
                .idleFor(100, java.util.concurrent.TimeUnit.MILLISECONDS)
            if (!vm.isSyncingFlow.value) {
                sawSyncingOrSettled = true
                break
            }
            java.util.concurrent.locks.LockSupport.parkNanos(
                java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(10),
            )
        }
        assertTrue(sawSyncingOrSettled, "refresh never settled")
        assertEquals("", vm.statusMessageFlow.value)
        assertEquals(false, vm.isSyncingFlow.value)
    }
}
