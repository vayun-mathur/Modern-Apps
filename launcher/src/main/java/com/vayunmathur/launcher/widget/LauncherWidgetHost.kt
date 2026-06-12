package com.vayunmathur.launcher.widget

import android.appwidget.AppWidgetHost
import android.appwidget.AppWidgetHostView
import android.appwidget.AppWidgetProviderInfo
import android.content.Context

class LauncherWidgetHost(context: Context, hostId: Int = 1024) : AppWidgetHost(context, hostId) {

    fun allocateId(): Int = allocateAppWidgetId()

    fun getHostView(context: Context, widgetId: Int, info: AppWidgetProviderInfo): AppWidgetHostView {
        return createView(context, widgetId, info)
    }
}
