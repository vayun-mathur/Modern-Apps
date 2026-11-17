package com.vayunmathur.education.platform

import com.vayunmathur.education.Route
import com.vayunmathur.library.util.NavBackStack

/**
 * Walking deeper along the catalog chain replaces a same-class detail instead of stacking it.
 *
 * On a two-pane layout the list stays visible beside its detail, so tapping a second course
 * (or unit, or lesson) would otherwise pile Course on Course; back would then step through
 * stale details instead of unwinding.
 */
internal fun NavBackStack<Route>.pushChain(route: Route) {
    if (backStack.isNotEmpty() && last()::class == route::class) setLast(route) else add(route)
}
