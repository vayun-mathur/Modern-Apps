package com.vayunmathur.appstore.util

import android.app.Application
import androidx.lifecycle.ViewModelStore
import androidx.room3.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.appstore.data.AppDatabase
import com.vayunmathur.appstore.tv.TvStoreViewModel
import kotlin.test.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Clearing a ViewModelStore clears both store ViewModels: their
 * onCleared releases the Accrescent gRPC channel, which must be a
 * safe no-op when no channel was ever opened (offline start).
 * Separate class so the store clear cannot race another test's jobs.
 */
@RunWith(RobolectricTestRunner::class)
class OnClearedTest {

    private fun db(app: Application): AppDatabase =
        Room.inMemoryDatabaseBuilder(app, AppDatabase::class.java)
            .setDriver(BundledSQLiteDriver())
            .allowMainThreadQueries()
            .build()

    @Test
    fun `clearing the store clears both view models without throwing`() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        // Not closed: the ViewModels' scopes keep collecting DB flows;
        // the in-memory DB dies with the Robolectric sandbox.
        val phone = AppStoreViewModel(app, db(app))
        val tv = TvStoreViewModel(app, db(app))
        val store = ViewModelStore()
        store.put("phone", phone)
        store.put("tv", tv)
        store.clear()
    }
}
