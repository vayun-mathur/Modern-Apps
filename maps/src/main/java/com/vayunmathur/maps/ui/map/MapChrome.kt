package com.vayunmathur.maps.ui.map
import androidx.compose.ui.text.style.TextOverflow
import com.vayunmathur.maps.data.string

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.vayunmathur.library.ui.Card
import com.vayunmathur.library.ui.IconSearch
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.Spacing
import com.vayunmathur.library.ui.Text
import com.vayunmathur.maps.ui.ContactAddressButton
import com.vayunmathur.maps.ui.VoiceSearchButton
import com.vayunmathur.maps.R as MapsR

/**
 * The map's search entry point: a pill resting at the bottom of the screen.
 *
 * Not editable, and not the real field: tapping it raises the search sheet, which owns the
 * editable one. This is the collapsed half of that pair, so it carries the two shortcuts that
 * make sense before you have typed anything — a contact's address, and voice — and nothing else.
 *
 * It is drawn only while browsing. A selected place or a running trip hands the bottom of the
 * screen to its own sheet, and a second pill floating over that sheet would be two search boxes
 * arguing about which one you meant.
 */
@Composable
fun MapSearchBar(
    onOpenSearch: (query: String?) -> Unit,
    onContactAddress: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(shape = MaterialTheme.shapes.extraLarge, modifier = modifier.fillMaxWidth()) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.clickable { onOpenSearch(null) }.padding(start = Spacing.md),
        ) {
            IconSearch(Modifier.size(20.dp))
            Text(
                stringResource(MapsR.string.search_placeholder),
                modifier = Modifier.weight(1f).padding(horizontal = Spacing.sm),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            // Contact address shortcut (P17/P31): pick a contact's postal address, geocode it
            // and open the resolved place directly — never through the search box, so no
            // query prefill and no results list.
            ContactAddressButton(onAddress = onContactAddress)
            // Voice search (P8): a transcript opens the search sheet pre-filled.
            VoiceSearchButton(onResult = { onOpenSearch(it) })
        }
    }
}
