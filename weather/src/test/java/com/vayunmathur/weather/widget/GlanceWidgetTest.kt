package com.vayunmathur.weather.widget

import androidx.compose.runtime.Composable
import androidx.glance.appwidget.testing.unit.runGlanceAppWidgetUnitTest
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.vayunmathur.weather.widget.glance.WeatherBlobGlanceWidget
import com.vayunmathur.weather.widget.glance.WeatherGlanceWidget
import com.vayunmathur.weather.widget.glance.WidgetWeather
import androidx.test.core.app.ApplicationProvider
import org.junit.runner.RunWith
import kotlin.test.Test

/** Test subclasses exposing the widgets' protected Glance content. */
private class TestStandard : WeatherGlanceWidget() {
    @Composable fun Exposed(data: WidgetWeather?) = Content(data)
    @Composable fun ExposedPreview() = PreviewContent()
}

private class TestBlob : WeatherBlobGlanceWidget() {
    @Composable fun Exposed(data: WidgetWeather?) = Content(data)
    @Composable fun ExposedPreview() = PreviewContent()
}

@RunWith(AndroidJUnit4::class)
class GlanceWidgetTest {

    @Test fun standardWidgetWithData() {
        runGlanceAppWidgetUnitTest {
            setContext(ApplicationProvider.getApplicationContext())
            provideComposable {
                TestStandard().Exposed(WidgetWeather(22.0, 1, isDay = true))
            }
            awaitIdle()
        }
    }

    @Test fun standardWidgetNoDataAndPreview() {
        runGlanceAppWidgetUnitTest {
            setContext(ApplicationProvider.getApplicationContext())
            provideComposable { TestStandard().Exposed(null) }
            awaitIdle()
        }
        runGlanceAppWidgetUnitTest {
            setContext(ApplicationProvider.getApplicationContext())
            provideComposable { TestStandard().ExposedPreview() }
            awaitIdle()
        }
    }

    @Test fun blobWidgetWithData() {
        runGlanceAppWidgetUnitTest {
            setContext(ApplicationProvider.getApplicationContext())
            provideComposable {
                TestBlob().Exposed(WidgetWeather(34.0, 3, isDay = false))
            }
            awaitIdle()
        }
    }

    @Test fun blobWidgetNoDataAndPreview() {
        runGlanceAppWidgetUnitTest {
            setContext(ApplicationProvider.getApplicationContext())
            provideComposable { TestBlob().Exposed(null) }
            awaitIdle()
        }
        runGlanceAppWidgetUnitTest {
            setContext(ApplicationProvider.getApplicationContext())
            provideComposable { TestBlob().ExposedPreview() }
            awaitIdle()
        }
    }
}
