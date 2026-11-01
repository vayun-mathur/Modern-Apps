package com.vayunmathur.travel.ui

import androidx.compose.ui.res.pluralStringResource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.vayunmathur.library.ui.ExpandVisibility
import com.vayunmathur.library.ui.IconKeyboardArrowDown
import com.vayunmathur.library.ui.ListItem
import com.vayunmathur.library.ui.ListItemDefaults
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.Surface
import com.vayunmathur.library.ui.Text
import com.vayunmathur.library.ui.animatedColor
import com.vayunmathur.library.ui.animatedDp
import com.vayunmathur.library.ui.animatedFloat
import com.vayunmathur.library.ui.rememberIs24Hour
import com.vayunmathur.travel.R
import com.vayunmathur.travel.network.OfferDto
import com.vayunmathur.travel.network.SliceDto

/**
 * An outbound flight that expands, in place, into the return flights it can be
 * paired with (round trips are priced as fixed pairs). Same expanding-group
 * look as the contacts Groups page: the header recolors and its bottom corners
 * tighten as the attached list opens.
 *
 * @param outbound the cheapest round trip using this outbound flight.
 * @param returns the cheapest round trip per return flight for this outbound.
 */
@Composable
fun OutboundFlightCard(
    outbound: OfferDto,
    returns: List<OfferDto>,
    expanded: Boolean,
    onToggle: () -> Unit,
    onPickReturn: (OfferDto) -> Unit,
) {
    val open = expanded && returns.isNotEmpty()
    val headerColor = animatedColor(
        if (open) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceVariant
    )
    val bottomRadius = animatedDp(if (open) 4.dp else 16.dp)
    Column {
        Surface(
            onClick = onToggle,
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp, 16.dp, bottomRadius, bottomRadius),
            color = headerColor,
        ) {
            ListItem(
                content = {
                    outbound.slices.firstOrNull()?.let { LegLines(it, outbound.owner) }
                },
                supportingContent = {
                    Text(pluralStringResource(R.plurals.return_flight_options, returns.size, returns.size))
                },
                leadingContent = { AirlineLogo(outbound.ownerLogoUrl, outbound.ownerIata) },
                trailingContent = {
                    Column(horizontalAlignment = Alignment.End) {
                        RoundTripPrice(outbound, from = true)
                        IconKeyboardArrowDown(Modifier.rotate(animatedFloat(if (open) 180f else 0f)))
                    }
                },
                colors = ListItemDefaults.colors(containerColor = Color.Transparent),
            )
        }
        // The 2dp gap belongs to the expanding block so it disappears with it.
        ExpandVisibility(visible = open) {
            Column {
                Spacer(Modifier.height(2.dp))
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                returns.forEachIndexed { i, offer ->
                    Surface(
                        onClick = { onPickReturn(offer) },
                        modifier = Modifier.fillMaxWidth(),
                        shape = attachedShape(i, returns.size),
                        color = MaterialTheme.colorScheme.surfaceContainerHighest,
                    ) {
                        ListItem(
                            content = {
                                offer.slices.getOrNull(1)?.let { LegLines(it, offer.slices[1].segments.firstOrNull()?.carrier.orEmpty()) }
                            },
                            leadingContent = { AirlineLogo("", offer.slices.getOrNull(1)?.segments?.firstOrNull()?.carrierIata.orEmpty()) },
                            trailingContent = { RoundTripPrice(offer, from = false) },
                            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                        )
                    }
                }
                }
            }
        }
    }
}

/** Carrier, route + times, then stops · duration for one leg. */
@Composable
internal fun LegLines(slice: SliceDto, carrier: String) {
    val is24 = rememberIs24Hour()
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        if (carrier.isNotBlank()) {
            Text(carrier, style = MaterialTheme.typography.titleSmall)
        }
        Text(
            "${slice.origin} → ${slice.destination}  ·  ${formatTime(slice.departureAt, is24)} – ${formatTime(slice.arrivalAt, is24)}",
            style = MaterialTheme.typography.bodyMedium,
        )
        Text(
            listOf(stopsLabel(slice.stops), formatDuration(slice.durationMinutes))
                .filter { it.isNotBlank() }.joinToString(" · "),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** Round-trip total, optionally as a "from" (cheapest) price. */
@Composable
internal fun RoundTripPrice(offer: OfferDto, from: Boolean) {
    Column(horizontalAlignment = Alignment.End) {
        if (from) {
            Text(
                stringResource(R.string.from),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text(
            formatMoney(offer.totalAmount, offer.currency),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.primary,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            stringResource(R.string.round_trip),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** Rows attached under the header: flat top on the first, rounded bottom on the last. */
private fun attachedShape(index: Int, size: Int, outer: Dp = 16.dp, inner: Dp = 4.dp): RoundedCornerShape {
    val bottom = if (index == size - 1) outer else inner
    return RoundedCornerShape(inner, inner, bottom, bottom)
}
