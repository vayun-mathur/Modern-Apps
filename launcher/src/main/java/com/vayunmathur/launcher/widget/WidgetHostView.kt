package com.vayunmathur.launcher.widget

import android.appwidget.AppWidgetHost
import android.appwidget.AppWidgetManager
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.viewinterop.AndroidView

@Composable
fun HostedWidget(
    appWidgetId: Int,
    widgetHost: AppWidgetHost,
    cellWidth: Dp,
    cellHeight: Dp,
    spanX: Int,
    spanY: Int,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current

    AndroidView(
        factory = { ctx ->
            val manager = AppWidgetManager.getInstance(ctx)
            val info = manager.getAppWidgetInfo(appWidgetId)
            if (info != null) {
                widgetHost.createView(ctx, appWidgetId, info).apply {
                    setAppWidget(appWidgetId, info)
                }
            } else {
                android.view.View(ctx)
            }
        },
        modifier = modifier
            .fillMaxWidth()
            .height(cellHeight * spanY)
    )
}
