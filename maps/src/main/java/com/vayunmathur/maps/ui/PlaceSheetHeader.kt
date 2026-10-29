package com.vayunmathur.maps.ui

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.vayunmathur.library.ui.ExternalIntents
import com.vayunmathur.library.ui.IconCall
import com.vayunmathur.library.ui.IconDirections
import com.vayunmathur.library.ui.IconGlobe
import com.vayunmathur.library.ui.IconSave
import com.vayunmathur.library.ui.IconShare
import com.vayunmathur.library.ui.IconShoppingCart
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.SecondaryTabRow
import com.vayunmathur.library.ui.Surface
import com.vayunmathur.library.ui.Tab
import com.vayunmathur.library.ui.Text
import com.vayunmathur.maps.R
import com.vayunmathur.maps.data.SavedPlace
import com.vayunmathur.maps.data.SpecificFeature
import com.vayunmathur.maps.data.google.GooglePoiInfo
import com.vayunmathur.maps.data.google.PoiSection
import com.vayunmathur.maps.ipc.rememberOrderDeepLink
import com.vayunmathur.maps.util.PlacePanelActions
import com.vayunmathur.maps.util.PlacePanelState
import com.vayunmathur.maps.util.SavedPlacesViewModel
import com.vayunmathur.maps.util.SelectedFeatureViewModel
import com.vayunmathur.maps.util.rememberPlacePanelState

/**
 * The fixed part of a place sheet: name, category · price, rating, open-now, the
 * Directions / Call / Website / Order / Share / Save row, and the Details / Photos /
 * Reviews tab row.
 *
 * A sibling of [PlaceSheet] rather than the top of it because it is what the sheet's
 * peek height is measured from — `FreeHeightBottomSheetScaffold` takes it as its
 * `sheetHeader` slot and peeks exactly tall enough to show it, so the boundary between
 * "always visible" and "revealed by expanding" is this function's edge.
 *
 * The tab row is on this side of that edge deliberately. It is chrome rather than
 * content — it does not say anything about the place, it says what else the sheet has —
 * and a peek that stopped at the action row would leave a user looking at a title and
 * six buttons with no sign that photos and reviews exist at all. The panel the row
 * switches is below the fold; the row itself is how you find out there is one.
 */
@Composable
fun PlaceSheetHeader(
    viewModel: SelectedFeatureViewModel,
    savedPlacesViewModel: SavedPlacesViewModel,
    inactiveNavigation: SpecificFeature.Route?,
    feature: SpecificFeature.Restaurant,
    modifier: Modifier = Modifier,
    requestDirections: () -> Unit,
) {
    PlaceSheetHeaderContent(
        viewModel, savedPlacesViewModel, inactiveNavigation, feature, modifier,
        phone = feature.phone, website = feature.website, requestDirections = requestDirections,
    )
}

@Composable
fun PlaceSheetHeader(
    viewModel: SelectedFeatureViewModel,
    savedPlacesViewModel: SavedPlacesViewModel,
    inactiveNavigation: SpecificFeature.Route?,
    feature: SpecificFeature.GenericPlace,
    modifier: Modifier = Modifier,
    requestDirections: () -> Unit,
) {
    PlaceSheetHeaderContent(
        viewModel, savedPlacesViewModel, inactiveNavigation, feature, modifier,
        phone = feature.phone, website = feature.website, requestDirections = requestDirections,
    )
}

/**
 * Stateless header: the same fixed part as [PlaceSheetHeader], driven by
 * [PlacePanelState] instead of the ViewModels, so previews can render it with
 * literal state. The ViewModel overloads above are the only callers that bind
 * real state; everything else passes through untouched.
 */
@Composable
fun PlaceSheetHeader(
    panelState: PlacePanelState,
    panelActions: PlacePanelActions,
    inactiveNavigation: SpecificFeature.Route?,
    feature: SpecificFeature.Restaurant,
    modifier: Modifier = Modifier,
    requestDirections: () -> Unit,
) = PlaceSheetHeaderContent(
    panelState, panelActions, inactiveNavigation, feature, modifier,
    phone = feature.phone, website = feature.website, requestDirections = requestDirections,
)

/**
 * Stateless header: the same fixed part as [PlaceSheetHeader], driven by
 * [PlacePanelState] instead of the ViewModels, so previews can render it with
 * literal state. The ViewModel overloads above are the only callers that bind
 * real state; everything else passes through untouched.
 */
@Composable
fun PlaceSheetHeader(
    panelState: PlacePanelState,
    panelActions: PlacePanelActions,
    inactiveNavigation: SpecificFeature.Route?,
    feature: SpecificFeature.GenericPlace,
    modifier: Modifier = Modifier,
    requestDirections: () -> Unit,
) = PlaceSheetHeaderContent(
    panelState, panelActions, inactiveNavigation, feature, modifier,
    phone = feature.phone, website = feature.website, requestDirections = requestDirections,
)

@Composable
private fun PlaceSheetHeaderContent(
    viewModel: SelectedFeatureViewModel,
    savedPlacesViewModel: SavedPlacesViewModel,
    inactiveNavigation: SpecificFeature.Route?,
    feature: SpecificFeature.RoutableFeature,
    modifier: Modifier,
    phone: String?,
    website: String?,
    requestDirections: () -> Unit,
) {
    val panelState = rememberPlacePanelState(viewModel, savedPlacesViewModel)
    val panelActions = remember(feature) {
        object : PlacePanelActions {
            override fun setPoiSection(section: PoiSection) {
                viewModel.setPoiSection(section)
            }
            override fun addSaved() {
                savedPlacesViewModel.addSaved(feature)
            }
            override fun removeSaved(place: SavedPlace) {
                savedPlacesViewModel.removeSaved(place)
            }
        }
    }
    PlaceSheetHeaderContent(
        panelState = panelState,
        panelActions = panelActions,
        inactiveNavigation = inactiveNavigation,
        feature = feature,
        modifier = modifier,
        phone = phone,
        website = website,
        requestDirections = requestDirections,
    )
}

@Composable
private fun PlaceSheetHeaderContent(
    panelState: PlacePanelState,
    panelActions: PlacePanelActions,
    inactiveNavigation: SpecificFeature.Route?,
    feature: SpecificFeature.RoutableFeature,
    modifier: Modifier,
    phone: String?,
    website: String?,
    requestDirections: () -> Unit,
) {
    val poi = panelState.poi
    val selectedSection = panelState.poiSection
    val context = LocalContext.current
    // If this is a restaurant/food place, ask fooddelivery whether it's orderable
    // (off the main thread, null-safe). Absent/not-orderable → null → no Order button.
    val orderDeepLink by rememberOrderDeepLink(context, feature, poi?.category)

    Column(modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        PlaceHeader(feature.name, poi, feature as? SpecificFeature.GenericPlace)
        PlaceActionRow(
            feature = feature,
            // OSM first, Google as the fallback. These used to be handed the OSM
            // values only, which were always null — so Call and Website were dead
            // buttons even when the enrichment had both.
            phone = phone ?: poi?.phone,
            website = website ?: poi?.website,
            inactiveNavigation = inactiveNavigation,
            isSaved = panelState.isSaved(feature),
            savedMatch = panelState.savedMatch(feature),
            onAddSaved = { panelActions.addSaved() },
            onRemoveSaved = { panelActions.removeSaved(it) },
            requestDirections = requestDirections,
            orderDeepLink = orderDeepLink,
        )
        val tabs = poiTabs(poi)
        val section = resolvePoiSection(selectedSection, poi)
        // One tab is no choice, so there is nothing to show and nothing for the peek to
        // make room for — a place Google knows nothing about peeks shorter by exactly the
        // height of the row it does not need.
        if (tabs.size > 1) {
            SecondaryTabRow(
                selectedTabIndex = tabs.indexOf(section),
                modifier = Modifier.fillMaxWidth(),
            ) {
                tabs.forEach { tab ->
                    Tab(
                        selected = tab == section,
                        onClick = { panelActions.setPoiSection(tab) },
                        text = {
                            Text(
                                stringResource(
                                    when (tab) {
                                        PoiSection.DETAILS -> R.string.poi_tab_details
                                        PoiSection.PHOTOS -> R.string.poi_photos_header
                                        PoiSection.REVIEWS -> R.string.poi_reviews_header
                                    }
                                )
                            )
                        },
                    )
                }
            }
        }
    }
}

/**
 * The tabs worth offering for [poi]: Details always, the other two only when the
 * enrichment actually came back with something behind them.
 */
internal fun poiTabs(poi: GooglePoiInfo?): List<PoiSection> = buildList {
    add(PoiSection.DETAILS)
    if (poi?.photoUrls?.isNotEmpty() == true) add(PoiSection.PHOTOS)
    if (poi?.reviews?.isNotEmpty() == true || poi?.featuredReview != null) add(PoiSection.REVIEWS)
}

/**
 * [selected], or Details if it is no longer on offer.
 *
 * The enrichment arrives after the sheet opens and a re-scrape can come back thinner, so
 * the selected tab can stop existing under the user. Both the tab row and the panel it
 * switches resolve through this, or they would disagree about what is showing.
 */
internal fun resolvePoiSection(selected: PoiSection, poi: GooglePoiInfo?): PoiSection =
    if (selected in poiTabs(poi)) selected else PoiSection.DETAILS

@Composable
private fun PlaceHeader(
    name: String,
    poi: GooglePoiInfo?,
    offline: SpecificFeature.GenericPlace?,
    modifier: Modifier = Modifier,
) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(name, style = MaterialTheme.typography.titleLarge)

        val subtitle = listOfNotNull(poi?.category?.ifBlank { null }, poi?.priceText?.ifBlank { null })
            .joinToString(" \u00B7 ")
        if (subtitle.isNotBlank()) {
            Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }

        // The archive's rating first, so it shows offline and with Google turned off.
        // Rating and count come from the same source, never one from each.
        val (rating, reviewCount) = offline?.rating?.let { it to offline.reviewCount }
            ?: (poi?.rating to poi?.reviewCount)
        rating?.let {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                RatingStars(it)
                Text(
                    stringResource(R.string.reviews_summary, it, reviewCount ?: 0),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        // Open-now status, from Google when it has one. The OSM hours row carries a
        // status line of its own, but that now sits behind the Details tab, so this
        // stays on the header unconditionally rather than deferring to it.
        poi?.statusText?.ifBlank { null }?.let { status ->
            val color = when (poi.openNow) {
                true -> MaterialTheme.colorScheme.tertiary
                false -> MaterialTheme.colorScheme.error
                null -> MaterialTheme.colorScheme.onSurfaceVariant
            }
            Text(status, style = MaterialTheme.typography.bodyMedium, color = color)
        }
    }
}
