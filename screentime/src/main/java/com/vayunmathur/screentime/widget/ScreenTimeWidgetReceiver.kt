package com.vayunmathur.screentime.widget

import android.content.Context
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.state.updateAppWidgetState
import androidx.glance.state.PreferencesGlanceStateDefinition
import com.vayunmathur.library.widgets.MaWidgetReceiver
import com.vayunmathur.screentime.platform.AppTimers
import com.vayunmathur.screentime.platform.UsageAccess
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

// No periodic refresh: content changes only when usage accrues, and the coordinator
// pushes an update on every reconcile that matters (see WidgetRefresh).
/** Glance widget receiver hosting the screen-time widget. */
class ScreenTimeGlanceWidgetReceiver : MaWidgetReceiver<ScreenTimeGlanceWidget>(
    widgetClass = ScreenTimeGlanceWidget::class,
    receiverClass = ScreenTimeGlanceWidgetReceiver::class,
    hourly = false,
    widgetFactory = ::ScreenTimeGlanceWidget,
)

/**
 * Pushes fresh totals into the widget.
 *
 * Called from the coordinator after reconcile so the widget tracks timers, pauses and
 * wind-down without polling UsageStats on its own schedule.
 */
object WidgetRefresh {

    fun refresh(context: Context) {
        val app = context.applicationContext
        CoroutineScope(Dispatchers.IO).launch {
            UsageAccess.ensure(app)
            val timers = AppTimers(app)
            val byPackage = timers.usageTodayByPackage() ?: emptyMap()
            val total = byPackage.values.sum() / 60_000L
            val top = byPackage.maxByOrNull { it.value }
            val topLabel = top?.key?.let { labelFor(app, it) }
            val topMinutes = (top?.value ?: 0L) / 60_000L
            runCatching {
                val manager = GlanceAppWidgetManager(app)
                val widget = ScreenTimeGlanceWidget()
                val ids = manager.getGlanceIds(ScreenTimeGlanceWidget::class.java)
                for (id in ids) {
                    updateAppWidgetState(app, PreferencesGlanceStateDefinition, id) { prefs ->
                        prefs.toMutablePreferences().apply {
                            this[TotalMinutesKey] = total
                            if (topLabel != null) {
                                this[TopLabelKey] = topLabel
                                this[TopMinutesKey] = topMinutes
                            }
                        }
                    }
                    widget.update(app, id)
                }
            }.onFailure {
                // The widget host may be absent (headless MAOS builds); usage tracking must
                // never depend on a home-screen widget rendering.
            }
        }
    }

    private fun labelFor(context: Context, packageName: String): String =
        runCatching {
            val info = context.packageManager.getApplicationInfo(packageName, 0)
            context.packageManager.getApplicationLabel(info).toString()
        }.getOrDefault(packageName)
}
