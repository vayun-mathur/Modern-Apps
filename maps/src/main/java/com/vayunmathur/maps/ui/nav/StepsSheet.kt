package com.vayunmathur.maps.ui.nav
import com.vayunmathur.maps.data.string
import com.vayunmathur.maps.platform.formatDistance

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vayunmathur.library.ui.Card
import com.vayunmathur.library.ui.CardDefaults
import com.vayunmathur.library.ui.HorizontalDivider
import com.vayunmathur.library.ui.IconButton
import com.vayunmathur.library.ui.IconClose
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.Text
import com.vayunmathur.maps.R
import com.vayunmathur.maps.ui.iconContent
import com.vayunmathur.maps.domain.RouteService

/**
 * Full turn-by-turn step list. Shows every remaining
 * maneuver with its icon, instruction, and distance. Steps already passed are
 * dimmed. Rendered as an overlay card the driver can dismiss.
 */
@Composable
fun StepsSheet(
    steps: List<RouteService.Step>,
    currentStepIndex: Int,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    route: RouteService.Route? = null,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                stringResource(R.string.nav_steps_title),
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClose) { IconClose() }
        }
        HorizontalDivider()
        // Route elevation profile (WS-G), when the graph carried DEM data. Renders nothing for a
        // flat/transit route, so it is safe to place unconditionally above the step list.
        if (route != null && route.elevationProfile.size >= 2) {
            ElevationChart(
                profile = route.elevationProfile,
                ascentMeters = route.ascentMeters,
                descentMeters = route.descentMeters,
            )
            HorizontalDivider()
        }
        LazyColumn(Modifier.fillMaxWidth()) {
            itemsIndexed(steps) { index, step ->
                val dim = index < currentStepIndex
                val alpha = if (dim) 0.4f else 1f
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    val icon = step.navInstruction.maneuver.iconContent()
                    val tint = MaterialTheme.colorScheme.onSurface.copy(alpha = alpha)
                    if (icon != null) {
                        icon(Modifier.size(28.dp), tint)
                        Spacer(Modifier.width(16.dp))
                    } else {
                        Spacer(Modifier.width(44.dp))
                    }
                    Text(
                        step.navInstruction.instructions,
                        modifier = Modifier.weight(1f),
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = alpha),
                    )
                    Spacer(Modifier.width(12.dp))
                    Text(
                        formatDistance(step.distanceMeters),
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = alpha),
                        fontSize = 13.sp,
                    )
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f))
            }
            item { Spacer(Modifier.size(12.dp)) }
        }
    }
}
