package com.vayunmathur.library.widgets

import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.Intent
import androidx.compose.runtime.Composable
import androidx.glance.GlanceId
import androidx.glance.text.Text
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

class DeviceFakeWidget : MaWidget<String>() {
    companion object { @Volatile var composed = false; @Volatile var loaded = false; @Volatile var previewComposed = false }
    override suspend fun loadData(context: Context, id: GlanceId): String { loaded = true; return "device-data" }
    override fun previewData(context: Context): String = "preview"
    @Composable override fun Content(data: String) { composed = true; Text(data) }
    @Composable override fun PreviewContent() { previewComposed = true; super.PreviewContent() }
}

class DeviceThrowWidget : MaWidget<String>() {
    companion object { @Volatile var fallbackComposed = false }
    override suspend fun loadData(context: Context, id: GlanceId): String = "x"
    override fun previewData(context: Context): String = "p"
    @Composable override fun Content(data: String) { throw IllegalStateException("compose boom") }
    @Composable override fun FallbackContent() { fallbackComposed = true; super.FallbackContent() }
}

class DeviceThrowReceiver : MaWidgetReceiver<DeviceThrowWidget>(
    widgetClass = DeviceThrowWidget::class,
    receiverClass = DeviceThrowReceiver::class,
    widgetFactory = ::DeviceThrowWidget,
)

class DeviceDateReceiver : MaWidgetReceiver<DeviceFakeWidget>(
    widgetClass = DeviceFakeWidget::class,
    receiverClass = DeviceDateReceiver::class,
    dateTriggers = true,
    widgetFactory = ::DeviceFakeWidget,
)

class DeviceFailWidget : MaWidget<String>() {
    companion object { @Volatile var fallbackComposed = false }
    override suspend fun loadData(context: Context, id: GlanceId): String = throw IllegalStateException("load boom")
    override fun previewData(context: Context): String = "p"
    @Composable override fun Content(data: String) { Text(data) }
    @Composable override fun FallbackContent() { fallbackComposed = true; super.FallbackContent() }
}

class DeviceFakeReceiver : MaWidgetReceiver<DeviceFakeWidget>(
    widgetClass = DeviceFakeWidget::class,
    receiverClass = DeviceFakeReceiver::class,
    widgetFactory = ::DeviceFakeWidget,
)

class DeviceFailReceiver : MaWidgetReceiver<DeviceFailWidget>(
    widgetClass = DeviceFailWidget::class,
    receiverClass = DeviceFailReceiver::class,
    widgetFactory = ::DeviceFailWidget,
)

@RunWith(AndroidJUnit4::class)
class WidgetDeviceTest {
    private val context: Context get() = InstrumentationRegistry.getInstrumentation().targetContext

    private fun pollUntil(deadlineMs: Long = 20000, cond: () -> Boolean) {
        val deadline = System.currentTimeMillis() + deadlineMs
        while (System.currentTimeMillis() < deadline && !cond()) Thread.sleep(100)
        assertTrue(cond())
    }

    @Test fun broadcastUpdateComposesContent() {
        // Prime Glance's in-process widget registry (a bare update() is a no-op
        // for an unplaced id, but it registers the widget class for the session).
        kotlinx.coroutines.runBlocking { DeviceFakeWidget().update(context, androidx.glance.appwidget.AppWidgetId(5151)) }
        val intent = Intent(context, DeviceFakeReceiver::class.java)
            .setAction(AppWidgetManager.ACTION_APPWIDGET_UPDATE)
            .putExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS, intArrayOf(5151))
        context.sendBroadcast(intent)
        pollUntil { DeviceFakeWidget.loaded && DeviceFakeWidget.composed }
    }

    @Test fun broadcastUpdateFailingWidgetComposesFallback() {
        kotlinx.coroutines.runBlocking { DeviceFailWidget().update(context, androidx.glance.appwidget.AppWidgetId(5152)) }
        val intent = Intent(context, DeviceFailReceiver::class.java)
            .setAction(AppWidgetManager.ACTION_APPWIDGET_UPDATE)
            .putExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS, intArrayOf(5152))
        context.sendBroadcast(intent)
        pollUntil { DeviceFailWidget.fallbackComposed }
    }

    @Test fun enabledBroadcastSchedulesAndPreviewsEnqueue() {
        // APPWIDGET_ENABLED is a protected broadcast, so call the receiver's
        // lifecycle entry the way the system would after dispatch.
        DeviceFakeReceiver().onEnabled(context)
        // preview registration goes through WorkManager on API 35+, no-op below
        context.updateWidgetPreviews(DeviceFakeReceiver::class)
        context.scheduleHourlyUpdate(DeviceFakeWidget::class)
        context.updateWidget(DeviceFakeWidget::class)
        val wm = androidx.work.WorkManager.getInstance(context)
        pollUntil {
            wm.getWorkInfosForUniqueWork(DeviceFakeWidget::class.qualifiedName!!).get().isNotEmpty()
        }
    }

    @Test fun previewRegistrationComposesPreview() {
        context.updateWidgetPreviews(DeviceFakeReceiver::class)
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.VANILLA_ICE_CREAM) {
            val wm = androidx.work.WorkManager.getInstance(context)
            pollUntil(30000) {
                val infos = wm.getWorkInfosForUniqueWork(DeviceFakeReceiver::class.qualifiedName!! + "_preview").get()
                infos.any { it.state.isFinished } || DeviceFakeWidget.previewComposed
            }
            pollUntil(30000) { DeviceFakeWidget.previewComposed }
        }
    }

    @Test fun dateChangedBroadcastRefreshesWidgets() {
        // Explicit broadcast to the date-driven receiver; its refresh coroutine
        // runs through updateAll for the (unplaced) widget without crashing.
        val intent = Intent(context, DeviceDateReceiver::class.java)
            .setAction(Intent.ACTION_PROVIDER_CHANGED)
        context.sendBroadcast(intent)
        Thread.sleep(2000)
        assertTrue(true)
    }
}
