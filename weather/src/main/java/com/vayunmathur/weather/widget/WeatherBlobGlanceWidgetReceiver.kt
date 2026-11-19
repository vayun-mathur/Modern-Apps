package com.vayunmathur.weather.widget

import android.content.Context
import com.vayunmathur.library.widgets.MaWidgetReceiver
import com.vayunmathur.weather.data.WeatherRefreshWorker

/** Weather Blob Glance Widget Receiver: a ma widget receiver implementation for weather blob glance widget receiver. */
class WeatherBlobGlanceWidgetReceiver : MaWidgetReceiver<WeatherBlobGlanceWidget>(
    widgetClass = WeatherBlobGlanceWidget::class,
    receiverClass = WeatherBlobGlanceWidgetReceiver::class,
    widgetFactory = ::WeatherBlobGlanceWidget,
) {
    override fun onEnabledExtra(context: Context) {
        WeatherRefreshWorker.scheduleHourlyRefresh(context)
    }
}
