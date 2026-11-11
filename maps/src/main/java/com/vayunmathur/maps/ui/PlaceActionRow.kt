package com.vayunmathur.maps.ui

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
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
import com.vayunmathur.library.ui.Surface
import com.vayunmathur.library.ui.Text
import com.vayunmathur.maps.R
import com.vayunmathur.library.ui.R as UiR
import com.vayunmathur.maps.data.SavedPlace
import com.vayunmathur.maps.data.SpecificFeature

@Composable
internal fun PlaceActionRow(
    feature: SpecificFeature.RoutableFeature,
    phone: String?,
    website: String?,
    inactiveNavigation: SpecificFeature.Route?,
    isSaved: Boolean,
    savedMatch: SavedPlace?,
    onAddSaved: () -> Unit,
    onRemoveSaved: (SavedPlace) -> Unit,
    requestDirections: () -> Unit,
    orderDeepLink: String?,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current

    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        PlaceAction(
            Modifier.weight(1f),
            { IconDirections() },
            stringResource(if (inactiveNavigation == null) R.string.directions else R.string.add_stop_to_route),
            onClick = requestDirections,
        )
        phone?.let {
            PlaceAction(Modifier.weight(1f), { IconCall() }, stringResource(UiR.string.call)) {
                goto(context, "tel:$it")
            }
        }
        website?.let {
            PlaceAction(Modifier.weight(1f), { IconGlobe() }, stringResource(R.string.place_action_website)) {
                goto(context, it)
            }
        }
        // Order (P19): only present when fooddelivery reports this place orderable.
        orderDeepLink?.let { uri ->
            PlaceAction(Modifier.weight(1f), { IconShoppingCart() }, stringResource(R.string.place_action_order)) {
                goto(context, uri)
            }
        }
        PlaceAction(Modifier.weight(1f), { IconShare() }, stringResource(UiR.string.share)) {
            sharePlace(context, feature)
        }
        PlaceAction(
            Modifier.weight(1f),
            { IconSave(tint = if (isSaved) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface) },
            stringResource(if (isSaved) UiR.string.saved else UiR.string.save),
        ) {
            if (isSaved) {
                savedMatch?.let { onRemoveSaved(it) }
            } else {
                onAddSaved()
            }
        }
    }
}

@Composable
private fun PlaceAction(
    modifier: Modifier = Modifier,
    icon: @Composable () -> Unit,
    label: String,
    onClick: () -> Unit,
) {
    androidx.compose.foundation.layout.Column(
        modifier
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Surface(shape = CircleShape, color = MaterialTheme.colorScheme.surfaceVariant, modifier = Modifier.size(44.dp)) {
            Box(contentAlignment = Alignment.Center) { icon() }
        }
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

private fun sharePlace(context: Context, feature: SpecificFeature.RoutableFeature) {
    val pos = feature.position
    // A neutral, clickable OpenStreetMap link (the map's own data source) rather than a
    // Google Maps URL: the app no longer points people at Google for places.
    val url = "https://www.openstreetmap.org/?mlat=${pos.latitude}&mlon=${pos.longitude}" +
        "#map=$SHARE_ZOOM/${pos.latitude}/${pos.longitude}"
    val body = context.getString(R.string.place_share_text, feature.name, url)
    val send = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_TEXT, body)
    }
    val chooser = Intent.createChooser(send, feature.name).apply {
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    ExternalIntents.launch(context, chooser)
}

/** Zoom of the shared OpenStreetMap link: street level. */
private const val SHARE_ZOOM = 17
