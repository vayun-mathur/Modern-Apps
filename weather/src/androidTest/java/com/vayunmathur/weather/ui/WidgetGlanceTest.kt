package com.vayunmathur.weather.ui

import androidx.glance.appwidget.AppWidgetId
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.vayunmathur.weather.widget.glance.WeatherBlobGlanceWidget
import com.vayunmathur.weather.widget.glance.WeatherGlanceWidget
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Exercises both Glance widgets' real provideGlance path on-device (loadData
 * from the Room cache + Glance RemoteViews composition), which Robolectric
 * cannot run. An unbound app-widget id is used: composition must complete
 * without throwing even though no host ever displays the RemoteViews.
 */
@RunWith(AndroidJUnit4::class)
class WidgetGlanceTest {

    @Test
    fun glanceWidgetsProvideWithoutCrash() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        runBlocking {
            WeatherGlanceWidget().provideGlance(context, AppWidgetId(99001))
            WeatherBlobGlanceWidget().provideGlance(context, AppWidgetId(99002))
        }
        assertTrue(true)
    }
}
