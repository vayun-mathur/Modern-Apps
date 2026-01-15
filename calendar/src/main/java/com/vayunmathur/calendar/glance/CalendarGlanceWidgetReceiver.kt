package com.vayunmathur.calendar.glance

import androidx.glance.appwidget.GlanceAppWidgetReceiver

class CalendarGlanceWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: CalendarGlanceWidget = CalendarGlanceWidget()
}

