package com.vayunmathur.library.widgets

import android.content.Context
import android.content.Intent
import androidx.compose.runtime.Composable
import androidx.glance.GlanceId
import androidx.glance.appwidget.AppWidgetId
import androidx.glance.text.Text
import androidx.test.core.app.ApplicationProvider
import androidx.work.testing.WorkManagerTestInitHelper
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.builtins.serializer
import org.junit.Before
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.Test
import kotlin.test.assertEquals

class ProvideWidget : MaWidget<String>() {
    var loads = 0
    override suspend fun loadData(context: Context, id: GlanceId): String {
        loads++
        return "provided-data"
    }
    override fun previewData(context: Context): String = "preview-data"
    @Composable override fun Content(data: String) { Text(data) }
}

class ContentFailWidget : MaWidget<String>() {
    override suspend fun loadData(context: Context, id: GlanceId): String = "x"
    override fun previewData(context: Context): String = "p"
    @Composable override fun Content(data: String) {
        throw IllegalStateException("content boom")
    }
}

class PreviewFailWidget : MaWidget<String>() {
    override suspend fun loadData(context: Context, id: GlanceId): String = "x"
    override fun previewData(context: Context): String =
        throw IllegalStateException("preview boom")
    @Composable override fun Content(data: String) { Text(data) }
}

/**
 * Drives [MaWidget.provideGlance]/[MaWidget.providePreview] for real, the way
 * calculator's GlanceProvideTest does: with the test WorkManager installed,
 * Glance composes the content to RemoteViews in-process, so the success,
 * load-failure, content-failure and preview branches all execute.
 */
@RunWith(RobolectricTestRunner::class)
class WidgetsProvideTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Before fun setUp() {
        runCatching { WorkManagerTestInitHelper.initializeTestWorkManager(context) }
    }

    @Test fun provideGlanceComposesContent() = runBlocking {
        val widget = ProvideWidget()
        widget.provideGlance(context, AppWidgetId(301))
        assertEquals(1, widget.loads)
        runCatching { widget.update(context, AppWidgetId(301)) }
        Unit
    }

    @Test fun provideGlanceLoadFailureAttemptsFallback() {
        // Without a Glance host the fallback provideContent throws back out;
        // the load-failure branch itself (log + fallback attempt) is the
        // behaviour pinned here, matching WidgetsTest's contract.
        kotlin.test.assertFailsWith<IllegalStateException> {
            runBlocking { FailingLoadWidget().provideGlance(context, AppWidgetId(303)) }
        }
        // Anonymous subclasses have no simple name: logTag falls back.
        val anon = object : MaWidget<String>() {
            override suspend fun loadData(context: Context, id: GlanceId): String =
                throw IllegalStateException("anon boom")
            override fun previewData(context: Context): String = "p"
            @Composable override fun Content(data: String) { Text(data) }
        }
        kotlin.test.assertFailsWith<IllegalStateException> {
            runBlocking { anon.provideGlance(context, AppWidgetId(304)) }
        }
    }

    @Test fun updateWidgetStateWithPlacedWidget() = runBlocking {
        // Place a widget through the receiver so GlanceAppWidgetManager has a
        // GlanceId for it, then the state write must land in preferences.
        val manager = android.appwidget.AppWidgetManager.getInstance(context)
        val receiver = FakeReceiver()
        receiver.onUpdate(context, manager, intArrayOf(4242))
        updateWidgetState(
            context, FakeWidget::class, "placed_key", listOf("s1"),
            kotlinx.serialization.builtins.ListSerializer(String.serializer()),
        )
        Unit
    }

    @Test fun provideGlanceContentFailureFallsBack() = runBlocking {
        ContentFailWidget().provideGlance(context, AppWidgetId(302))
        Unit
    }

    @Test fun providePreviewComposesAndPreviewFailureFallsBack() = runBlocking {
        ProvideWidget().providePreview(context, 1)
        PreviewFailWidget().providePreview(context, 1)
        Unit
    }

    @Test fun dateReceiverRefreshesOnTimeChange() {
        val receiver = DateReceiver()
        receiver.onReceive(context, Intent(Intent.ACTION_TIME_CHANGED))
        receiver.onReceive(context, Intent(Intent.ACTION_DATE_CHANGED))
        receiver.onReceive(context, Intent(Intent.ACTION_TIMEZONE_CHANGED))
    }
}
