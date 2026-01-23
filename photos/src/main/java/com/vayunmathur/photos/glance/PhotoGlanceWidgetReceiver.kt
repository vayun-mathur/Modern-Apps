package com.vayunmathur.photos.glance

import androidx.glance.appwidget.GlanceAppWidgetReceiver

class PhotoGlanceWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: PhotoGlanceWidget = PhotoGlanceWidget()
}

