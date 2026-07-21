package com.vayunmathur.travel.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vayunmathur.library.ui.Button
import com.vayunmathur.library.ui.ExperimentalMaterial3Api
import com.vayunmathur.library.ui.Icon
import com.vayunmathur.library.ui.IconButton
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.Scaffold
import com.vayunmathur.library.ui.Text
import com.vayunmathur.library.ui.TopAppBar
import com.vayunmathur.library.util.NavBackStack
import com.vayunmathur.travel.Route
import com.vayunmathur.travel.network.SeatCabinDto
import com.vayunmathur.travel.network.SeatElementDto
import com.vayunmathur.travel.network.SeatRowDto
import com.vayunmathur.travel.util.TravelViewModel

private val CELL = 44.dp
private val GAP = 4.dp
private val AISLE = 22.dp

/**
 * Duffel gives variable element counts per section (e.g. a 3-4-3 cabin), and does
 * not pad rows to a uniform width, so we can't render elements 1:1 and expect
 * columns to line up. Instead we derive a canonical grid per cabin: the ordered
 * set of seat-letters in each section. Seats are then placed under their own
 * column; amenities (lavatory, galley, exit row…) are rendered as a band that
 * spans their whole section block, matching how the aircraft is actually laid out.
 */
private class CabinLayout(
    val sectionLetters: List<List<Char>>,
    val sectionWidths: List<Int>,
)

private fun seatLetter(designator: String): Char? = designator.lastOrNull { it.isLetter() }

private fun computeLayout(cabin: SeatCabinDto): CabinLayout {
    val sectionCount = cabin.rows.maxOfOrNull { it.sections.size } ?: 0
    val letters = List(sectionCount) { sortedSetOf<Char>() }
    val maxCounts = IntArray(sectionCount)
    cabin.rows.forEach { row ->
        row.sections.forEachIndexed { s, section ->
            maxCounts[s] = maxOf(maxCounts[s], section.elements.size)
            section.elements.forEach { el ->
                if (el.isSeat) seatLetter(el.designator)?.let { letters[s].add(it) }
            }
        }
    }
    val sectionLetters = letters.map { it.toList() }
    val widths = sectionLetters.mapIndexed { i, l ->
        if (l.isNotEmpty()) l.size else maxCounts[i].coerceAtLeast(1)
    }
    return CabinLayout(sectionLetters, widths)
}

/** Total width a section block occupies (its columns plus the gaps between them). */
private fun blockWidth(columns: Int) = CELL * columns + GAP * (columns - 1).coerceAtLeast(0)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SeatMapPage(
    backStack: NavBackStack<Route>,
    viewModel: TravelViewModel,
    route: Route.SeatMap,
) {
    val state by viewModel.seatMap.collectAsStateWithLifecycle()
    val selectedSeats by viewModel.selectedSeats.collectAsStateWithLifecycle()

    LaunchedEffect(route.offerId) { viewModel.loadSeatMaps(route.offerId) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Choose seats") },
                navigationIcon = {
                    IconButton(onClick = { backStack.pop() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        val cabins = state.cabins.filter { it.segmentId == route.segmentId }
        if (state.loading || state.error != null || cabins.isEmpty()) {
            StatusBox(
                loading = state.loading,
                error = state.error,
                isEmpty = !state.loading && state.error == null && cabins.isEmpty(),
                modifier = Modifier.padding(padding),
                emptyMessage = "No seat map available for this flight.",
            )
            return@Scaffold
        }
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()),
        ) {
            SeatLegend()
            cabins.forEach { cabin ->
                CabinView(cabin, route.segmentId, selectedSeats) { viewModel.toggleSeat(route.segmentId, it) }
            }
            Button(
                onClick = { backStack.pop() },
                modifier = Modifier.fillMaxWidth().padding(16.dp),
            ) { Text("Done") }
        }
    }
}

@Composable
private fun SeatLegend() {
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

@Composable
private fun CabinView(
    cabin: SeatCabinDto,
    segmentId: String,
    selectedSeats: Map<String, SeatElementDto>,
    onToggle: (SeatElementDto) -> Unit,
) {
    if (cabin.cabinClass.isNotBlank()) {
        Text(
            cabin.cabinClass.replace('_', ' ').replaceFirstChar { it.uppercase() },
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        )
    }
    val layout = remember(cabin) { computeLayout(cabin) }
    // Rows share one horizontal scroll so columns stay aligned across the cabin.
    Column(
        Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(GAP),
    ) {
        cabin.rows.forEach { row -> SeatRowView(row, layout, segmentId, selectedSeats, onToggle) }
    }
}

@Composable
private fun SeatRowView(
    row: SeatRowDto,
    layout: CabinLayout,
    segmentId: String,
    selectedSeats: Map<String, SeatElementDto>,
    onToggle: (SeatElementDto) -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        layout.sectionWidths.indices.forEach { s ->
            if (s > 0) Spacer(Modifier.width(AISLE))
            val elements = row.sections.getOrNull(s)?.elements ?: emptyList()
            val letters = layout.sectionLetters[s]
            if (elements.any { it.isSeat } && letters.isNotEmpty()) {
                Row(horizontalArrangement = Arrangement.spacedBy(GAP)) {
                    letters.forEach { letter ->
                        val seat = elements.firstOrNull { it.isSeat && seatLetter(it.designator) == letter }
                        if (seat != null) {
                            val selected = selectedSeats.containsKey("$segmentId|${seat.designator}")
                            SeatChip(seat, selected) { onToggle(seat) }
                        } else {
                            Spacer(Modifier.size(CELL))
                        }
                    }
                }
            } else {
                val type = elements.firstOrNull { it.type.isNotBlank() && it.type != "empty" }?.type
                AmenityBand(type, layout.sectionWidths[s])
            }
        }
    }
}

@Composable
private fun SeatChip(seat: SeatElementDto, selected: Boolean, onClick: () -> Unit) {
    val bg = when {
        selected -> MaterialTheme.colorScheme.primary
        seat.available -> MaterialTheme.colorScheme.secondaryContainer
        else -> MaterialTheme.colorScheme.surfaceVariant
    }
    val fg = when {
        selected -> MaterialTheme.colorScheme.onPrimary
        seat.available -> MaterialTheme.colorScheme.onSecondaryContainer
        else -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
    }
    val price = seat.totalAmount?.let { amt ->
        formatMoney(amt, seat.totalCurrency ?: "USD")
    }
    Box(
        Modifier
            .size(CELL)
            .clip(RoundedCornerShape(6.dp))
            .background(bg)
            .let { if (seat.available) it.clickable { onClick() } else it },
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                seat.designator,
                style = MaterialTheme.typography.labelSmall,
                color = fg,
                textAlign = TextAlign.Center,
                fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
            )
            if (seat.available && price != null) {
                Text(price, fontSize = 8.sp, color = fg, textAlign = TextAlign.Center)
            }
        }
    }
}

/** A facility (lavatory, galley, exit row…) drawn as a band spanning its section. */
@Composable
private fun AmenityBand(type: String?, columns: Int) {
    val label = when (type) {
        "lavatory" -> "Lavatory"
        "galley" -> "Galley"
        "closet" -> "Closet"
        "stairs" -> "Stairs"
        "bassinet" -> "Bassinet"
        "exit_row" -> "Exit"
        "restricted_seat_general" -> "" // handled as seat elsewhere
        else -> ""
    }
    val width = blockWidth(columns)
    if (label.isBlank()) {
        Spacer(Modifier.width(width).height(CELL))
        return
    }
    val isExit = type == "exit_row"
    Box(
        Modifier
            .width(width)
            .height(CELL)
            .clip(RoundedCornerShape(6.dp))
            .let {
                if (isExit) it.background(MaterialTheme.colorScheme.tertiaryContainer)
                else it.border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(6.dp))
            },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = if (isExit) FontWeight.Bold else FontWeight.Normal,
            color = if (isExit) MaterialTheme.colorScheme.onTertiaryContainer
            else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
