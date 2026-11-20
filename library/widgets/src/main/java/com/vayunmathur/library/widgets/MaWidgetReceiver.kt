package com.vayunmathur.library.widgets

import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.Intent
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.updateAll
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlin.reflect.KClass

/**
 * Shared Glance receiver shell: hourly refresh scheduling, preview
 * registration, and optional date/time-change refresh.
 *
 * Every widget receiver used to copy the same `onEnabled` (schedule hourly
 * update + register widget-picker previews) and `onUpdate` (re-ensure the
 * schedule after reboot/work cancellation). Date-driven widgets (calendar)
 * additionally handled `DATE_CHANGED / TIME_SET / TIMEZONE_CHANGED /
 * PROVIDER_CHANGED` broadcasts. All of that lives here now.
 *
 * A receiver keeps one line per widget:
 * `class FooReceiver : MaWidgetReceiver<FooWidget>(FooWidget::class,
 * FooReceiver::class)`, plus `dateTriggers = true` for date-driven widgets,
 * `hourly = false` for push-updated widgets, `requiresNetwork = false` for
 * hourly widgets whose data is purely on-device, `previews = false` when the
 * picker preview comes from `previewLayout` XML, and [onEnabledExtra] for
 * extras like weather's refresh-worker scheduling.
 */
abstract class MaWidgetReceiver<W : GlanceAppWidget>(
    private val widgetClass: KClass<W>,
    private val receiverClass: KClass<out GlanceAppWidgetReceiver>,
    private val hourly: Boolean = true,
    private val requiresNetwork: Boolean = true,
    private val dateTriggers: Boolean = false,
    private val previews: Boolean = true,
    private val widgetFactory: () -> W,
) : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = widgetFactory()

    override fun onEnabled(context: Context) {
        super.onEnabled(context)
        ensureScheduled(context)
        if (previews) context.updateWidgetPreviews(receiverClass)
        onEnabledExtra(context)
    }

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray,
    ) {
        super.onUpdate(context, appWidgetManager, appWidgetIds)
        // Re-ensure periodic work is scheduled (covers reboot / work cancellation).
        ensureScheduled(context)
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        if (!dateTriggers) return
        when (intent.action) {
            Intent.ACTION_DATE_CHANGED,
            Intent.ACTION_TIME_CHANGED,
            Intent.ACTION_TIMEZONE_CHANGED,
            Intent.ACTION_PROVIDER_CHANGED -> {
                val pending = goAsync()
                CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
                    try {
                        widgetFactory().updateAll(context)
                    } catch (_: Exception) {
                    } finally {
                        pending?.finish()
                    }
                }
            }
        }
    }

    /** Extra per-app work on first enable (e.g. schedule a refresh worker). */
    protected open fun onEnabledExtra(context: Context) {
    }

    private fun ensureScheduled(context: Context) {
        if (hourly) context.scheduleHourlyUpdate(widgetClass, requiresNetwork)
    }
}
