package com.vayunmathur.clock.widget

import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import androidx.compose.runtime.Composable
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.LocalContext
import androidx.glance.action.clickable
import androidx.glance.appwidget.AndroidRemoteViews
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.layout.Box
import androidx.glance.layout.fillMaxSize
import com.vayunmathur.clock.MainActivity
import com.vayunmathur.clock.R
import com.vayunmathur.library.widgets.MaWidget
import com.vayunmathur.library.widgets.MaWidgetReceiver

/**
 * Material You analog clock widget. The dial, the hands and the orbiting second-hand dot are
 * drawn and ticked by the framework [android.widget.AnalogClock] in
 * [R.layout.analog_clock_widget], which Glance embeds verbatim through [AndroidRemoteViews]:
 * Glance has no self-ticking clock, and recomposing the widget once a second to fake one is
 * exactly what widget-update throttling exists to stop. Glance's job here is the layout wrapper
 * and the tap-to-open-clock action.
 */
class AnalogClockGlanceWidget : MaWidget<Unit>() {
    // No composed picker preview: the preview comes from `previewLayout` in
    // analog_clock_widget_info.xml. Composing AndroidRemoteViews inside setWidgetPreviews
    // crashes the preview host on API 35+, and a preview without ticking hands would be a
    // downgrade from the real layout the launcher already renders.
    override val composePreview: Boolean = false

    override suspend fun loadData(context: Context, id: GlanceId): Unit = Unit

    override fun previewData(context: Context): Unit = Unit

    @Composable
    override fun Content(data: Unit) {
        AnalogClockContent()
    }
}

@Composable
private fun AnalogClockContent() {
    val context = LocalContext.current
    Box(
        modifier = GlanceModifier
            .fillMaxSize()
            .clickable(
                actionStartActivity(
                    Intent(context, MainActivity::class.java).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                    }
                )
            ),
    ) {
        AndroidRemoteViews(RemoteViews(context.packageName, R.layout.analog_clock_widget))
    }
}

/**
 * Receiver for [AnalogClockGlanceWidget]. Keeps the `...WidgetProvider` name (rather than the
 * `...GlanceWidgetReceiver` the other apps use) because the launcher keys placed widgets by
 * component name: renaming it would silently drop every clock widget already on a home screen.
 */
class AnalogClockWidgetProvider : MaWidgetReceiver<AnalogClockGlanceWidget>(
    widgetClass = AnalogClockGlanceWidget::class,
    receiverClass = AnalogClockWidgetProvider::class,
    // No periodic refresh (the framework AnalogClock ticks itself) and no composed
    // picker preview (the preview comes from `previewLayout` XML in the widget info).
    hourly = false,
    previews = false,
    widgetFactory = ::AnalogClockGlanceWidget,
)
