package com.vayunmathur.screentime.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vayunmathur.library.util.NavBackStack
import com.vayunmathur.screentime.Route
import com.vayunmathur.screentime.platform.ScreenTimeViewModel

/** App-details destination: opens the route's package, then wires its controls. */
@Composable
internal fun AppDetailsEntry(
    viewModel: ScreenTimeViewModel,
    backStack: NavBackStack<Route>,
    route: Route.AppDetails,
) {
    LaunchedEffect(route.packageName) {
        viewModel.openDetail(route.packageName.ifBlank { null })
    }
    val state by viewModel.detailState.collectAsStateWithLifecycle()
    AppDetailsScreen(
        state = state,
        onBack = { backStack.pop() },
        onSetPeriod = viewModel::setPeriod,
        onStep = viewModel::stepAnchor,
        onTimerChange = { minutes ->
            state.packageName?.let { viewModel.setTimer(it, minutes) }
        },
        onPausedChange = { paused ->
            state.packageName?.let { viewModel.setAppPaused(it, paused) }
        },
    )
}

/** Wind-down destination and its schedule controls. */
@Composable
internal fun WindDownEntry(viewModel: ScreenTimeViewModel) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    WindDownScreen(
        state = state,
        onEnabledChange = { viewModel.setWindDown(enabled = it) },
        onStartChange = { h, m -> viewModel.setWindDown(startMinute = h * 60 + m) },
        onEndChange = { h, m -> viewModel.setWindDown(endMinute = h * 60 + m) },
        onToggleDay = { day ->
            viewModel.setWindDown(daysMask = state.windDown.daysMask xor (1 shl day))
        },
        onGrayscaleChange = { viewModel.setWindDown(grayscale = it) },
        onDndChange = { viewModel.setWindDown(doNotDisturb = it) },
    )
}
