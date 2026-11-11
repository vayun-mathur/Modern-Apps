package com.vayunmathur.maps.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.vayunmathur.library.ui.FilterChip
import com.vayunmathur.library.ui.FilterChipDefaults
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.Spacing
import com.vayunmathur.library.ui.Text
import com.vayunmathur.library.ui.rememberHaptics
import com.vayunmathur.maps.R
import com.vayunmathur.maps.util.PoiCategories

/**
 * One quick category: a display label, the offline index search [query] it runs on the
 * search page, the archive [kinds] it filters the drawn POI layers down to on the
 * browse screen, and the set of OSM POI [types] ([PoiCategories]) the offline
 * index is searched by.
 *
 * Two vocabularies because there are two consumers. [kinds] drives the renderer, which
 * filters on the archive's own kind names; [types] drives `PoiIndex`, whose numbering
 * predates the archive. `PoiCategories.typeOfKind` joins them, and the join is lossy in
 * both directions — expect a chip to select a slightly different set from each.
 */
data class MapCategory(
    val labelRes: Int,
    val query: String,
    val types: Set<Int>,
    val kinds: Set<String>,
)

/**
 * Shared quick-category definitions, reused by both the browse map screen and
 * the search page so the two stay in lock-step. On the
 * browse map a chip FILTERS the drawn POI layers to its [MapCategory.kinds];
 * on the search page it runs the [MapCategory.query] offline index search (P3).
 *
 * Gas, Hotels and ATMs only became real filters with the v3 archive: it is the first one
 * whose schema has a `fuel`, `hotel` or `atm` kind at all, so before it those three chips
 * narrowed the map to a set that could not contain their subject.
 */
val MAP_CATEGORIES: List<MapCategory> = listOf(
    MapCategory(R.string.search_category_restaurants, "restaurants", setOf(0, 2), setOf("restaurant", "fast_food")),
    MapCategory(R.string.search_category_coffee, "coffee", setOf(1), setOf("cafe")),
    MapCategory(R.string.search_category_gas, "gas station", setOf(6), setOf("fuel")),
    // The archive has no grocery kind of its own, so a corner shop rides along with the
    // supermarkets rather than the chip finding almost nothing in a dense city.
    MapCategory(R.string.search_category_groceries, "groceries", setOf(5), setOf("supermarket", "convenience")),
    MapCategory(R.string.search_category_hotels, "hotels", setOf(8), setOf("hotel")),
    // `bank` as well as `atm`: OSM tags plenty of cash machines only as the branch they sit in.
    MapCategory(R.string.search_category_atms, "atm", setOf(9), setOf("atm", "bank")),
)

/**
 * Horizontally-scrolling row of quick category chips. [onCategory] is invoked
 * with the tapped [MapCategory] — on browse this toggles the on-map POI filter,
 * on the search page it runs the category's offline index query. [selected] is the
 * currently-active category (shown highlighted), or null when none is active.
 *
 * [contentPadding] is the row's inset, and is applied *inside* the scroll rather than
 * around it. A margin on the modifier would clip the first and last chip against the
 * inset edge instead of letting them scroll past it.
 */
@Composable
fun CategoryChips(
    onCategory: (MapCategory) -> Unit,
    modifier: Modifier = Modifier,
    selected: MapCategory? = null,
    contentPadding: PaddingValues = PaddingValues(0.dp),
) {
    val haptics = rememberHaptics()
    Row(
        modifier = modifier.horizontalScroll(rememberScrollState()).padding(contentPadding),
        horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
    ) {
        // Material's unselected filter chip has a *transparent* container. On the
        // browse screen these float directly over the map, where that leaves the
        // label competing with POI pins and coloured roads showing through it, so
        // the container is filled explicitly.
        val colors = FilterChipDefaults.filterChipColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        )
        MAP_CATEGORIES.forEach { category ->
            FilterChip(
                selected = category == selected,
                onClick = {
                    // A chip tap changes what is drawn on the map, not what page you are on, so
                    // the confirmation is the only feedback that the tap landed.
                    haptics.confirm()
                    onCategory(category)
                },
                label = { Text(stringResource(category.labelRes)) },
                colors = colors,
            )
        }
    }
}
