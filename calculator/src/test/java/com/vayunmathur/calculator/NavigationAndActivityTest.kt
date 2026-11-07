package com.vayunmathur.calculator

import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.calculator.widget.CalculatorWidgetSettingsActivity
import android.appwidget.AppWidgetManager
import kotlinx.serialization.json.Json
import org.junit.Before
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
class NavigationAndActivityTest {

    @Before fun setUp() {
        runCatching {
            androidx.work.testing.WorkManagerTestInitHelper.initializeTestWorkManager(
                ApplicationProvider.getApplicationContext(),
            )
        }
    }

    @Test fun routeSerializationRoundTrips() {
        val json = Json
        assertEquals(
            Route.Graph,
            json.decodeFromString(Route.serializer(), json.encodeToString(Route.serializer(), Route.Graph)),
        )
        assertEquals(
            Route.Units,
            json.decodeFromString(Route.serializer(), json.encodeToString(Route.serializer(), Route.Units)),
        )
    }

    @Test fun mainActivityCreates() {
        val controller = Robolectric.buildActivity(MainActivity::class.java)
        controller.create().start().resume().visible()
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
        assertTrue(!controller.get().isFinishing)
        controller.pause().stop().destroy()
    }

    @Test fun mainActivityWithUnitsIntentCreates() {
        val intent = Intent(
            ApplicationProvider.getApplicationContext(),
            MainActivity::class.java,
        ).putExtra(com.vayunmathur.calculator.widget.UnitsGlanceWidget.EXTRA_OPEN_UNITS, true)
        val controller = Robolectric.buildActivity(MainActivity::class.java, intent)
        controller.create().start().resume().visible()
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
        assertTrue(!controller.get().isFinishing)
        // A second units deep-link arrives while running.
        controller.newIntent(intent)
        controller.pause().stop().destroy()
    }

    @Test fun widgetSettingsActivityWithIdShowsScreen() {
        val intent = Intent(
            ApplicationProvider.getApplicationContext(),
            CalculatorWidgetSettingsActivity::class.java,
        ).putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, 7)
        val controller = Robolectric.buildActivity(CalculatorWidgetSettingsActivity::class.java, intent)
        controller.create().start().resume()
        assertTrue(!controller.get().isFinishing)
        // The settings screen is set as content; pausing stops it cleanly.
        controller.pause().stop().destroy()
    }
}
