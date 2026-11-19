package com.vayunmathur.travel.ui
import com.vayunmathur.travel.R

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.vayunmathur.library.image.compose.AsyncImage
import com.vayunmathur.library.ui.ExperimentalMaterial3Api
import com.vayunmathur.library.ui.ElevatedCard
import com.vayunmathur.library.ui.IconNavigation
import com.vayunmathur.library.ui.LazyListScaffold
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.Text
import com.vayunmathur.library.ui.TopAppBar
import com.vayunmathur.library.util.sharedContainer
import com.vayunmathur.library.util.sharedText
import com.vayunmathur.travel.network.StaySearchResultDto
import com.vayunmathur.travel.platform.StayResultsActions
import com.vayunmathur.travel.platform.StaySearchState
import androidx.compose.ui.res.stringResource

/**
 * The hotel list, with no dependency on the ViewModel or the back stack so it can be
 * rendered from a `@Preview` — see `src/screenshotTest`, which is where the store listing
 * images come from.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StayResultsScreen(
    place: String,
    state: StaySearchState,
    actions: StayResultsActions,
) {
    LazyListScaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.hotels_in, place)) },
                navigationIcon = { IconNavigation { actions.back() } },
            )
        },
    ) {
        items(state.results) { result ->
            StayResultCard(result) { actions.openStay(result) }
        }
        if (state.loading || state.error != null || (state.hasSearched && state.results.isEmpty())) {
            item {
                StatusBox(
                    loading = state.loading,
                    error = state.error,
                    isEmpty = state.hasSearched && state.results.isEmpty(),
                    emptyMessage = "No hotels found for those dates.",
                )
            }
        }
    }
}

@Composable
private fun StayResultCard(result: StaySearchResultDto, onClick: () -> Unit) {
    ElevatedCard(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp).clickable { onClick() },
    ) {
        Column {
            if (result.photoUrl.isNotBlank()) {
                AsyncImage(
                    model = result.photoUrl,
                    contentDescription = result.name,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(16f / 9f)
                        .sharedContainer("travel-stay-photo-${result.id}"),
                )
            }
            Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.Top) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        result.name.ifBlank { stringResource(R.string.hotel) },
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.sharedText("travel-stay-name-${result.id}"),
                    )
                    Text(
                        starLabel(result.rating, result.reviewScore),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.sharedText("travel-stay-rating-${result.id}"),
                    )
                    if (result.address.isNotBlank()) {
                        Text(result.address, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    if (result.amenities.isNotEmpty()) {
                        Text(
                            result.amenities.take(4).joinToString(" · "),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text(stringResource(R.string.from), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(
                        formatMoney(result.cheapestAmount, result.cheapestCurrency),
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }
        }
    }
}
