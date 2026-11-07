package com.vayunmathur.appstore.util

import android.app.Application
import android.content.Intent
import androidx.room3.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.appstore.data.AppDatabase
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows

/**
 * openApp's fallback: a package with no launcher entry on this device
 * is handed to the Play Store listing instead of doing nothing, so the
 * row's Open button never reads as broken.
 */
@RunWith(RobolectricTestRunner::class)
class OpenAppActionTest {

    private lateinit var vm: AppStoreViewModel
    private lateinit var app: Application

    @BeforeTest
    fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        val db = Room.inMemoryDatabaseBuilder(app, AppDatabase::class.java)
            .setDriver(BundledSQLiteDriver())
            .allowMainThreadQueries()
            .build()
        vm = AppStoreViewModel(app, db)
    }

    @Test
    fun `openApp for a package with no launch intent opens its store listing`() {
        vm.openApp("com.example.notinstalled")
        val intent = Shadows.shadowOf(app).nextStartedActivity
        assertNotNull(intent)
        assertEquals(Intent.ACTION_VIEW, intent.action)
        assertTrue(
            intent.dataString!!.startsWith("market://details?id=com.example.notinstalled"),
            "was ${intent.dataString}",
        )
    }
}
