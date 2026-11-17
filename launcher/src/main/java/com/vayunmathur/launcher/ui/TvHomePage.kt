package com.vayunmathur.launcher.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import com.vayunmathur.launcher.platform.TvHomeViewModel

/**
 * The stateful half of the TV home screen.
 *
 * Every `XxxPage` in this package does the same three things and nothing else: read the state
 * off the ViewModel, hand the stateless `XxxScreen` that state plus the actions, and wire
 * navigation (the :launcher HomePage split).
 */
@Composable
fun TvHomePage(viewModel: TvHomeViewModel) {
    val state by viewModel.home.collectAsState()
    val context = LocalContext.current

    TvHomeScreen(
        state = state,
        onPressTile = viewModel::pressTile,
        onOpenAppStore = viewModel::openAppStore,
        onOpenInput = viewModel::openInput,
        onOpenSettings = viewModel::openSettings,
        onOpenNetworkSettings = viewModel::openNetworkSettings,
        onOpenEditMenu = viewModel::openEditMenu,
        onCloseEditMenu = viewModel::closeEditMenu,
        onStartMove = viewModel::startMove,
        onCancelMove = viewModel::cancelMove,
        onToggleReorder = viewModel::toggleReorder,
        onDisarmReorder = viewModel::disarmReorder,
        onMoveKey = viewModel::moveKey,
        onRemoveTile = viewModel::removeTile,
        onOpenAddMenu = viewModel::openAddMenu,
        onPickAddCandidate = viewModel::pickAddCandidate,
        onUninstallOrInfo = viewModel::uninstallOrInfo,
        onRequestHomeRole = remember(viewModel, context) {
            { viewModel.requestHomeRole(context as android.app.Activity) }
        },
    )
}

