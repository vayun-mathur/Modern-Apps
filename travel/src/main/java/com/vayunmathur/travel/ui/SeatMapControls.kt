package com.vayunmathur.travel.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.vayunmathur.library.ui.FilterChip
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.Text
import com.vayunmathur.travel.domain.seatKey
import com.vayunmathur.travel.network.SeatElementDto

/** A passenger who needs a seat, with the label shown in the picker. */
data class SeatPassenger(val id: String, val label: String)

@Composable
internal fun SeatPassengerPicker(
    passengers: List<SeatPassenger>,
    segmentId: String,
    selectedSeats: Map<String, SeatElementDto>,
    active: String,
    onSelect: (String) -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        passengers.forEach { p ->
            val seat = selectedSeats[seatKey(segmentId, p.id)]?.designator
            FilterChip(
                selected = p.id == active,
                onClick = { onSelect(p.id) },
                label = { Text(if (seat != null) "${p.label} · $seat" else p.label) },
            )
        }
    }
}

@Composable
internal fun SeatLegend() {
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        LegendSwatch(MaterialTheme.colorScheme.secondaryContainer, "Available")
        LegendSwatch(MaterialTheme.colorScheme.primary, "Selected")
        LegendSwatch(MaterialTheme.colorScheme.surfaceVariant, "Taken")
    }
}

@Composable
private fun LegendSwatch(color: Color, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(Modifier.size(16.dp).clip(RoundedCornerShape(4.dp)).background(color))
        Text(label, style = MaterialTheme.typography.bodySmall)
    }
}
