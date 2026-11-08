package com.vayunmathur.appstore.util

import android.app.Application
import android.content.Intent
import androidx.room3.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.appstore.data.AppDatabase
import com.vayunmathur.appstore.data.AppSource
import com.vayunmathur.appstore.data.UnifiedApp
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows

/**
 * Row actions as intents: opening, uninstalling, browsing, sharing and
 * the Play Store hand-off must fire the right intent (Robolectric's
 * shadow application records them), and a blank browser URL fires none.
 */
@RunWith(RobolectricTestRunner::class)
class ActionOpsTest {

    private lateinit var vm: AppStoreViewModel
    private lateinit var app: Application

    @BeforeTest
    fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        val db = Room.inMemoryDatabaseBuilder(app, AppDatabase::class.java)
            .setDriver(BundledSQLiteDriver())
            .allowMainThreadQueries()
            .build()
        // Not closed: the ViewModel scope keeps collecting DB flows; the
        // in-memory DB dies with the Robolectric sandbox.
        vm = AppStoreViewModel(app, db)
    }

    private fun nextStarted(): Intent? = Shadows.shadowOf(app).nextStartedActivity

    @Test
    fun `openInBrowser fires a view intent and a blank url fires none`() {
        vm.openInBrowser("https://example.org/app")
        val intent = nextStarted()
        assertNotNull(intent)
        assertEquals(Intent.ACTION_VIEW, intent.action)
        assertEquals("https://example.org/app", intent.dataString)

        vm.openInBrowser("   ")
        assertNull(nextStarted())
    }

    @Test
    fun `openInPlayStore tries the market intent first`() {
        vm.openInPlayStore("com.example.target")
        val intent = nextStarted()
        assertNotNull(intent)
        assertEquals(Intent.ACTION_VIEW, intent.action)
        assertTrue(
            intent.dataString!!.startsWith("market://details?id=com.example.target"),
            "was ${intent.dataString}",
        )
    }

    @Test
    fun `uninstallApp fires a delete intent for the package`() {
        vm.uninstallApp("com.example.target")
        val intent = nextStarted()
        assertNotNull(intent)
        assertEquals(Intent.ACTION_DELETE, intent.action)
        assertEquals("package:com.example.target", intent.dataString)
        assertTrue(intent.getBooleanExtra(Intent.EXTRA_RETURN_RESULT, false))
    }

    @Test
    fun `shareApp fires a chooser carrying the app link`() {
        vm.shareApp(
            UnifiedApp(
                packageName = "com.example.share",
                source = AppSource.FDROID,
                name = "Share App",
                website = "https://example.org/share",
            ),
        )
        val chooser = nextStarted()
        assertNotNull(chooser)
        assertEquals(Intent.ACTION_CHOOSER, chooser.action)
        val send = chooser.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
        assertNotNull(send)
        assertEquals(Intent.ACTION_SEND, send.action)
        assertTrue(
            send.getStringExtra(Intent.EXTRA_TEXT)!!.contains("https://example.org/share"),
        )
    }

    @Test
    fun `shareApp without a website falls back to the play link`() {
        vm.shareApp(
            UnifiedApp(
                packageName = "com.example.share",
                source = AppSource.FDROID,
                name = "Share App",
            ),
        )
        val chooser = nextStarted()
        assertNotNull(chooser)
        val send = chooser.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
        assertTrue(
            send!!.getStringExtra(Intent.EXTRA_TEXT)!!
                .contains("play.google.com/store/apps/details?id=com.example.share"),
        )
    }
}
