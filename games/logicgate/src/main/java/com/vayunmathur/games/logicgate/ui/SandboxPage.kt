package com.vayunmathur.games.logicgate.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.res.stringResource
import com.vayunmathur.games.logicgate.R
import com.vayunmathur.games.logicgate.Route
import com.vayunmathur.games.logicgate.platform.LogicViewModel
import com.vayunmathur.library.util.NavBackStack

/** Binds [LogicViewModel]'s components to the stateless [SandboxScreen]. */
@Composable
fun SandboxPage(backStack: NavBackStack<Route>, viewModel: LogicViewModel) {
    val state by viewModel.sandbox.collectAsState()
    val name = stringResource(R.string.lg_component_default_name, state.components.size + 1)
    val group = stringResource(R.string.lg_component_default_group)
    SandboxScreen(
        state = state,
        onOpen = { backStack.add(Route.Component(it)) },
        onCreate = { backStack.add(Route.Component(viewModel.createComponent(name, group))) },
        onOpenGameCenter = { backStack.add(Route.GameCenter) },
    )
}
