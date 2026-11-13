package com.vayunmathur.maps.ui

import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import com.vayunmathur.maps.ui.map.MapSearchBar
import com.vayunmathur.maps.ui.theme.MapChromeMetrics
import com.vayunmathur.maps.util.searchBiasPoint
import com.vayunmathur.maps.util.visibleBoundsOrWorld

/**
 * The search entry point, moved out of [MapContentBox] so no ui file exceeds
 * the [FileLength] limit. A member extension so it can read the page scope —
 * see MapPageScope for why the scope exists instead of widening it.
 *
 * Takes [modifier] instead of placing itself: the caller sits in the content
 * [Box][androidx.compose.foundation.layout.Box] and aligns it bottom-center.
 */
@Composable
internal fun MapPageScope.MapSearchBarSection(
    modifier: Modifier = Modifier,
) {
    // The search entry point, where the top app bar's used to be but at the other
    // end of the screen. Browse-only: an open sheet — either of them — owns the
    // bottom and brings its own field or its own title.
    if (searchBarVisible) {
        MapSearchBar(
            onOpenSearch = { query -> openSearch(query, null) },
            onContactAddress = { address ->
                val near = searchBiasPoint(camera.visibleBoundsOrWorld(), userPosition)
                searchViewModel.resolveAndSelect(address, near.latitude, near.longitude) { place ->
                    if (place != null) {
                        viewModel.stashRouteSelection()
                        // Fly there and open the peek pane, the same direct-open
                        // path a geo:/maps deep link takes.
                        viewModel.selectAndFocus(
                            place,
                            zoom = (camera.position.zoom).coerceAtLeast(14.0),
                        )
                    } else {
                        messenger.show(noResultsMessage)
                    }
                }
            },
            modifier = modifier
                // Measured, not assumed: the bar's height is what the FAB stack
                // and the scale bar have to clear, and it moves with the font
                // scale. Outside the padding, so the margin is included.
                .onSizeChanged { onSearchBarLift(it.height) }
                .padding(MapChromeMetrics.chromeMargin),
        )
    }
}
