package com.vayunmathur.weather.glance

import android.content.Context
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import com.vayunmathur.library.widgets.scheduleHourlyUpdate
import com.vayunmathur.weather.data.WeatherRefreshWorker

class WeatherGlanceWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: WeatherGlanceWidget = WeatherGlanceWidget()

    override fun onEnabled(context: Context) {
        super.onEnabled(context)
        context.scheduleHourlyUpdate(WeatherGlanceWidget::class)
        WeatherRefreshWorker.scheduleHourlyRefresh(context)
    }
}
