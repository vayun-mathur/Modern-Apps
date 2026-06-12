package com.vayunmathur.launcher.ui

import android.appwidget.AppWidgetHost
import android.graphics.drawable.Drawable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.vayunmathur.launcher.AppInfo
import com.vayunmathur.launcher.data.HomeScreenItem
import com.vayunmathur.launcher.data.WidgetItem
import com.vayunmathur.launcher.widget.HostedWidget

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun HomePages(
    pagerState: PagerState,
    pageCount: Int,
    gridColumns: Int,
    gridRows: Int,
    getPageItems: (Int) -> List<HomeScreenItem>,
    getPageWidgets: (Int) -> List<WidgetItem>,
    getAppInfo: (String) -> AppInfo?,
    widgetHost: AppWidgetHost?,
    onAppClick: (HomeScreenItem) -> Unit,
    onAppLongClick: (HomeScreenItem) -> Unit,
    onEmptyLongClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier) {
        HorizontalPager(
            state = pagerState,
            modifier = Modifier.weight(1f)
        ) { page ->
            HomePageGrid(
                items = getPageItems(page),
                widgets = getPageWidgets(page),
                gridColumns = gridColumns,
                gridRows = gridRows,
                getAppInfo = getAppInfo,
                widgetHost = widgetHost,
                onAppClick = onAppClick,
                onAppLongClick = onAppLongClick,
                onEmptyLongClick = onEmptyLongClick
            )
        }

        PageIndicator(
            pageCount = pageCount,
            currentPage = pagerState.currentPage,
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 8.dp)
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun HomePageGrid(
    items: List<HomeScreenItem>,
    widgets: List<WidgetItem>,
    gridColumns: Int,
    gridRows: Int,
    getAppInfo: (String) -> AppInfo?,
    widgetHost: AppWidgetHost?,
    onAppClick: (HomeScreenItem) -> Unit,
    onAppLongClick: (HomeScreenItem) -> Unit,
    onEmptyLongClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .combinedClickable(
                onClick = {},
                onLongClick = onEmptyLongClick
            )
            .padding(horizontal = 16.dp, vertical = 8.dp)
    ) {
        Column(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.SpaceEvenly
        ) {
            for (row in 0 until gridRows) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly
                ) {
                    for (col in 0 until gridColumns) {
                        val item = items.find { it.row == row && it.col == col }
                        val widget = widgets.find { it.row == row && it.col == col }

                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .padding(4.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            when {
                                item != null -> {
                                    val appInfo = getAppInfo(item.packageName)
                                    if (appInfo != null) {
                                        AppIcon(
                                            name = appInfo.name,
                                            icon = appInfo.icon,
                                            onClick = { onAppClick(item) },
                                            onLongClick = { onAppLongClick(item) }
                                        )
                                    }
                                }
                                widget != null && widgetHost != null -> {
                                    HostedWidget(
                                        appWidgetId = widget.appWidgetId,
                                        widgetHost = widgetHost,
                                        cellWidth = 80.dp,
                                        cellHeight = 80.dp,
                                        spanX = widget.spanX,
                                        spanY = widget.spanY
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PageIndicator(
    pageCount: Int,
    currentPage: Int,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        repeat(pageCount) { index ->
            Box(
                modifier = Modifier
                    .padding(horizontal = 4.dp)
                    .size(if (index == currentPage) 8.dp else 6.dp)
                    .clip(CircleShape)
                    .background(
                        if (index == currentPage) Color.White
                        else Color.White.copy(alpha = 0.5f)
                    )
            )
        }
    }
}
