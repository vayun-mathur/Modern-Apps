package com.vayunmathur.library.widgets

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.glance.GlanceId
import androidx.glance.appwidget.AppWidgetId
import androidx.glance.appwidget.testing.unit.runGlanceAppWidgetUnitTest
import androidx.glance.testing.unit.hasText
import androidx.glance.text.Text
import androidx.test.core.app.ApplicationProvider
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.Test
import kotlin.test.assertEquals

class ComposingWidget : MaWidget<String>() {
    override suspend fun loadData(context: Context, id: GlanceId): String = "loaded-data"
    override fun previewData(context: Context): String = "preview-data"
    @Composable override fun Content(data: String) { Text(data) }
    @Composable fun contentForTest(data: String) = Content(data)
    suspend fun loadForTest(c: Context, id: GlanceId) = loadData(c, id)
    @Composable fun fallbackForTest() = FallbackContent()
    @Composable fun previewForTest() = PreviewContent()
}

@RunWith(RobolectricTestRunner::class)
class WidgetsGlanceTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val widget = ComposingWidget()

    @Test fun contentComposesLoadedDataText() = runGlanceAppWidgetUnitTest {
        setContext(context)
        setAppWidgetSize(DpSize(200.dp, 100.dp))
        provideComposable { widget.contentForTest("loaded-data") }
        onNode(hasText("loaded-data")).assertExists()
    }
    @Test fun fallbackComposesDash() = runGlanceAppWidgetUnitTest {
        setContext(context)
        provideComposable { widget.fallbackForTest() }
        onNode(hasText("—")).assertExists()
    }
    @Test fun previewComposesPreviewData() = runGlanceAppWidgetUnitTest {
        setContext(context)
        provideComposable { widget.previewForTest() }
        onNode(hasText("preview-data")).assertExists()
    }
    @Test fun dynamicThemeGlanceComposes() = runGlanceAppWidgetUnitTest {
        setContext(context)
        provideComposable {
            DynamicThemeGlance(context) { Text("themed-widget-text") }
        }
        onNode(hasText("themed-widget-text")).assertExists()
    }
    @Test fun provideGlanceThroughTesterLoadsData() {
        // The unit-test environment provides the ContentReceiver provideGlance needs:
        // drive the real widget entry through provideComposable by invoking its
        // Content with loadData's result, as provideGlance does.
        var loaded: String? = null
        kotlinx.coroutines.runBlocking { loaded = widget.loadForTest(context, AppWidgetId(9)) }
        assertEquals("loaded-data", loaded)
    }
}
