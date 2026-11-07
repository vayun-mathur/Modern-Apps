package com.vayunmathur.networklocation

import android.content.Context
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlin.test.Test
import kotlin.test.assertNull
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.Robolectric

@RunWith(AndroidJUnit4::class)
class ServicesAndNavigationTest {

    @get:Rule
    val rule = createComposeRule()

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    private fun clearDbs() {
        for (n in listOf(OfflineDatabases.GEOCODER, OfflineDatabases.WIFI, OfflineDatabases.CELL)) {
            OfflineDatabases.file(context, n).delete()
        }
    }

    private fun installAllDbs() {
        writeGeocoderDb(OfflineDatabases.file(context, OfflineDatabases.GEOCODER))
        OfflineDatabases.file(context, OfflineDatabases.WIFI).writeBytes(byteArrayOf(1))
        OfflineDatabases.file(context, OfflineDatabases.CELL).writeBytes(byteArrayOf(1))
    }

    @Test
    fun `network location service binds to nothing before create and destroys cleanly`() {
        val service = NetworkLocationService()
        assertNull(service.onBind(null), "no provider exists until onCreate runs")
        service.onDestroy()
    }

    @Test
    fun `navigation starts on setup while databases are missing`() {
        clearDbs()
        rule.setContent { Navigation() }
        rule.onNodeWithText("Offline databases").assertIsDisplayed()
        rule.onNodeWithText("Download offline databases").assertExists()
    }

    @Test
    fun `navigation starts on status once databases are installed`() {
        installAllDbs()
        rule.setContent { Navigation() }
        rule.onNodeWithText("Offline databases").assertIsDisplayed()
        rule.onNodeWithText(
            "All offline databases are installed. Positioning and geocoding work without " +
                "a network connection.",
        ).assertIsDisplayed()
    }

    @Test
    fun `main activity creates its content`() {
        clearDbs()
        val controller = Robolectric.buildActivity(MainActivity::class.java).create()
        val activity = controller.get()
        org.junit.Assert.assertEquals(
            "com.vayunmathur.networklocation.MainActivity",
            activity.javaClass.name,
        )
        controller.destroy()
    }
}
