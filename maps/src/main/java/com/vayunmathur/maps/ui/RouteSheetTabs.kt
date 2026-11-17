package com.vayunmathur.maps.ui
import com.vayunmathur.maps.data.string

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.vayunmathur.library.ui.Button
import com.vayunmathur.library.ui.ButtonDefaults
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.Spacing
import com.vayunmathur.library.ui.Text
import com.vayunmathur.maps.R
import com.vayunmathur.maps.intents.RideHandoffContract

/**
 * "Book ride": hands the trip off to the MA taxi app, pre-filled (P20).
 *
 * The only action the rideshare segment offers — the ride is driven for the user, so
 * there is no step list and nothing to navigate. Shown only once the caller has
 * established the app is installed; the fare and pickup wait are on the segment itself,
 * and a provider that will not quote either simply leaves those lines off.
 */
@Composable
internal fun RideshareBooking(
    originLat: Double,
    originLng: Double,
    originLabel: String?,
    destLat: Double,
    destLng: Double,
    destLabel: String?,
) {
    val context = LocalContext.current
    Button(
        onClick = {
            goto(
                context,
                RideHandoffContract.bookingDeepLink(
                    originLat, originLng, originLabel, destLat, destLng, destLabel,
                ),
            )
        },
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.lg, vertical = Spacing.sm),
        colors = ButtonDefaults.buttonColors(
            containerColor = MaterialTheme.colorScheme.primary,
            contentColor = MaterialTheme.colorScheme.onPrimary,
        ),
    ) {
        Text(stringResource(R.string.route_taxi_book))
    }
}

/**
 * One segment of the mode selector: name on top, then two supporting lines.
 *
 * For a travel mode those lines are time and distance; for rideshare they are arrival
 * and price. Either may be missing — a route that is still being computed, or a fare
 * the provider would not quote — and the line is dropped rather than blanked so the
 * segments stay the same height.
 */
@Composable
internal fun ModeTab(label: String, primary: String?, secondary: String?) {
    Column(
        Modifier.padding(vertical = Spacing.sm),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(label, style = MaterialTheme.typography.labelLarge)
        if (primary != null) {
            Text(primary, style = MaterialTheme.typography.bodyMedium)
        }
        if (secondary != null) {
            Text(
                secondary,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
