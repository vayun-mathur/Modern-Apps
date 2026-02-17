package com.vayunmathur.photos.glance

import android.content.Context
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import com.vayunmathur.library.util.scheduleHourlyUpdate

class PhotoGlanceWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: PhotoGlanceWidget = PhotoGlanceWidget()

    override fun onEnabled(context: Context) {
        super.onEnabled(context)
        context.scheduleHourlyUpdate(PhotoGlanceWidget::class)
    }
}

