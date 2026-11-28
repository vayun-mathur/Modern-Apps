package com.vayunmathur.weather.domain

/**
 * How the home forecast lays out for the width it is actually given.
 *
 * Pure width math (no Android/Compose) so the breakpoints are unit-testable
 * and so [com.vayunmathur.weather.ui.HomeScreen] can key off its available
 * constraints rather than the activity window: a screen hosted in a narrow
 * pane must lay out for that pane, never for the wider window around it.
 * The thresholds mirror the shared window-size breakpoints (600dp medium,
 * 840dp expanded).
 */
enum class HomeLayout {
    /** Portrait phones: modal locations drawer, single forecast column. */
    Compact,

    /** Landscape phones and small tablets: full-width forecast, two card columns. */
    Medium,

    /** Large tablets/desktop: permanent locations panel beside a two-column forecast. */
    Expanded,
}

/** The [HomeLayout] for an available width of [widthDp] dp. */
fun homeLayoutForWidthDp(widthDp: Int): HomeLayout = when {
    widthDp >= 840 -> HomeLayout.Expanded
    widthDp >= 600 -> HomeLayout.Medium
    else -> HomeLayout.Compact
}

/** Only expanded windows pin the locations list beside the forecast. */
fun homeUsesPermanentLocationsPanel(layout: HomeLayout): Boolean =
    layout == HomeLayout.Expanded

/** Card columns in the forecast body: one on compact, two on anything wider. */
fun forecastColumnCount(layout: HomeLayout): Int =
    if (layout == HomeLayout.Compact) 1 else 2

/**
 * Width of the permanent locations panel on expanded windows: 30% of the
 * window, clamped to 280–360dp so the panel stays a list (not a second
 * forecast) and the forecast always keeps at least ~60% of the width.
 */
fun locationsPanelWidthDp(windowWidthDp: Int): Int =
    (windowWidthDp * 30 / 100).coerceIn(280, 360)

/** Width left for the forecast beside the panel (full width without a panel). */
fun forecastWidthDp(windowWidthDp: Int, layout: HomeLayout): Int =
    if (homeUsesPermanentLocationsPanel(layout)) {
        windowWidthDp - locationsPanelWidthDp(windowWidthDp)
    } else {
        windowWidthDp
    }
