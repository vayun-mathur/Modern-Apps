package com.vayunmathur.launcher.ui

import android.appwidget.AppWidgetHost
import android.appwidget.AppWidgetManager
import android.content.Intent
import android.os.Build
import android.view.HapticFeedbackConstants
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import com.vayunmathur.launcher.LauncherViewModel
import com.vayunmathur.launcher.widget.WidgetPicker

@Composable
fun LauncherScreen(
    viewModel: LauncherViewModel,
    widgetHost: AppWidgetHost?
) {
    val apps by viewModel.apps.collectAsState()
    val query by viewModel.query.collectAsState()
    val searchResults by viewModel.searchResults.collectAsState()
    val isSearching by viewModel.isSearching.collectAsState()
    val isDrawerOpen by viewModel.isDrawerOpen.collectAsState()
    val focusSearch by viewModel.focusSearch.collectAsState()
    val dockItems by viewModel.dockItems.collectAsState()
    val pageCount by viewModel.pageCount.collectAsState()
    val gridColumns by viewModel.gridColumns.collectAsState()
    val gridRows by viewModel.gridRows.collectAsState()
    val homeContextMenuVisible by viewModel.homeContextMenuVisible.collectAsState()
    val showWidgetPicker by viewModel.showWidgetPicker.collectAsState()
    val contextMenuApp by viewModel.contextMenuApp.collectAsState()
    val contextMenuDockApp by viewModel.contextMenuDockApp.collectAsState()
    val openFolder by viewModel.openFolder.collectAsState()
    val folders by viewModel.folders.collectAsState()

    val context = LocalContext.current
    val view = LocalView.current
    val pagerState = rememberPagerState(pageCount = { pageCount })

    Box(modifier = Modifier.fillMaxSize()) {
        WallpaperBackground()

        Column(
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.statusBars)
                .windowInsetsPadding(WindowInsets.navigationBars)
                .launcherGestures(
                    onSwipeUp = { viewModel.openDrawer() },
                    onSwipeDown = {
                        try {
                            @Suppress("DEPRECATION")
                            val sbService = context.getSystemService("statusbar")
                            sbService?.javaClass?.getMethod("expandNotificationsPanel")?.invoke(sbService)
                        } catch (_: Exception) {}
                    }
                )
        ) {
            AtAGlanceWidget()

            HomePages(
                pagerState = pagerState,
                pageCount = pageCount,
                gridColumns = gridColumns,
                gridRows = gridRows,
                getPageItems = { viewModel.getPageItems(it) },
                getPageWidgets = { viewModel.getPageWidgets(it) },
                getAppInfo = { viewModel.getAppInfo(it) },
                widgetHost = widgetHost,
                onAppClick = { item ->
                    context.packageManager.getLaunchIntentForPackage(item.packageName)?.let {
                        context.startActivity(it)
                    }
                },
                onAppLongClick = { item ->
                    view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                    viewModel.showAppContextMenu(item)
                },
                onEmptyLongClick = {
                    view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                    viewModel.showHomeContextMenu()
                },
                modifier = Modifier.weight(1f)
            )

            SearchPill(onClick = { viewModel.openDrawer(focusSearch = true) })

            DockBar(
                dockItems = dockItems,
                getIcon = { viewModel.getIcon(it) },
                onAppClick = { item ->
                    context.packageManager.getLaunchIntentForPackage(item.packageName)?.let {
                        context.startActivity(it)
                    }
                },
                onAppLongClick = { item ->
                    view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                    viewModel.showDockAppContextMenu(item)
                }
            )
        }

        // App Drawer overlay
        if (isDrawerOpen) {
            AppDrawer(
                apps = apps,
                query = query,
                searchResults = searchResults,
                isSearchActive = isSearching,
                onQueryChange = viewModel::setQuery,
                onSearchActiveChange = viewModel::setSearching,
                onDismiss = { viewModel.closeDrawer() },
                onAppClick = { app ->
                    context.packageManager.getLaunchIntentForPackage(app.packageName)?.let {
                        context.startActivity(it)
                    }
                },
                focusSearch = focusSearch
            )
        }

        // Home context menu
        HomeContextMenu(
            expanded = homeContextMenuVisible,
            onDismiss = { viewModel.hideHomeContextMenu() },
            onWidgets = { viewModel.showWidgetPicker() }
        )

        // App context menu (home screen items)
        contextMenuApp?.let { item ->
            AppContextMenu(
                expanded = true,
                packageName = item.packageName,
                onDismiss = { viewModel.hideAppContextMenu() },
                onRemove = {
                    viewModel.removeFromPage(item)
                    viewModel.hideAppContextMenu()
                }
            )
        }

        // Dock app context menu
        contextMenuDockApp?.let { item ->
            AppContextMenu(
                expanded = true,
                packageName = item.packageName,
                onDismiss = { viewModel.hideDockAppContextMenu() },
                onRemove = {
                    viewModel.removeFromDock(item)
                    viewModel.hideDockAppContextMenu()
                }
            )
        }

        // Widget picker
        if (showWidgetPicker) {
            WidgetPicker(
                onDismiss = { viewModel.hideWidgetPicker() },
                onWidgetSelected = { providerInfo ->
                    viewModel.hideWidgetPicker()
                    if (widgetHost != null) {
                        val widgetId = widgetHost.allocateAppWidgetId()
                        val manager = AppWidgetManager.getInstance(context)
                        val bound = manager.bindAppWidgetIdIfAllowed(widgetId, providerInfo.provider)
                        if (bound) {
                            viewModel.addWidget(
                                appWidgetId = widgetId,
                                page = pagerState.currentPage,
                                row = 0, col = 0,
                                spanX = (providerInfo.minWidth / 80).coerceAtLeast(1),
                                spanY = (providerInfo.minHeight / 80).coerceAtLeast(1)
                            )
                        }
                    }
                }
            )
        }

        // Folder dialog
        openFolder?.let { folder ->
            val folderItems = viewModel.getFolderItems(folder.id)
            FolderDialog(
                folder = folder,
                folderApps = folderItems,
                getAppInfo = { viewModel.getAppInfo(it) },
                onAppClick = { item ->
                    context.packageManager.getLaunchIntentForPackage(item.packageName)?.let {
                        context.startActivity(it)
                    }
                    viewModel.closeFolder()
                },
                onRename = { viewModel.renameFolder(folder, it) },
                onDismiss = { viewModel.closeFolder() }
            )
        }
    }
}
