package com.vayunmathur.launcher.wear.ui

/** Watch launcher destinations; screens are routes, never selection state. */
sealed interface WearRoute {
    data object Home : WearRoute
    data object AllApps : WearRoute
    data object Settings : WearRoute
}
