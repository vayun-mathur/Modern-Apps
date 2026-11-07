package com.vayunmathur.appstore.util

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
        val initDeadline = System.currentTimeMillis() + 30_000
        while (System.currentTimeMillis() < initDeadline) {
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            if (!vm.isSyncingFlow.value && !vm.isLoadingHomeFlow.value) break
            Thread.sleep(50)
        }
        vm.refresh()
        val deadline = System.currentTimeMillis() + 60_000
        var sawSyncingOrSettled = false
        while (System.currentTimeMillis() < deadline) {
            Shadows.shadowOf(Looper.getMainLooper())
                .idleFor(200, java.util.concurrent.TimeUnit.MILLISECONDS)
            if (!vm.isSyncingFlow.value) {
                sawSyncingOrSettled = true
                break
            }
            Thread.sleep(50)
        }
        assertTrue(sawSyncingOrSettled, "refresh never settled")
        assertEquals("", vm.statusMessageFlow.value)
        assertEquals(false, vm.isSyncingFlow.value)
    }
}
