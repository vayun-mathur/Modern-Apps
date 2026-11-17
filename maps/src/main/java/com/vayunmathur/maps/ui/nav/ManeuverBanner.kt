package com.vayunmathur.maps.ui.nav
import com.vayunmathur.maps.domain.RouteService
import com.vayunmathur.maps.data.string
import com.vayunmathur.maps.platform.formatDistance

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vayunmathur.library.ui.Card
import com.vayunmathur.library.ui.CardDefaults
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.Spacing
import com.vayunmathur.library.ui.Text
import com.vayunmathur.maps.R
import com.vayunmathur.maps.ui.iconContent
import com.vayunmathur.maps.domain.NavigationProgress

/**
 * Rich turn maneuver banner. Shows the upcoming
 * maneuver icon, distance-to-maneuver, the primary instruction (with a route
 * shield when the road carries a ref), a "then" peek at the following
 * maneuver, and a lane-guidance strip from the Rust router's lane data (P5a).
 */
@Composable
fun ManeuverBanner(
    progress: NavigationProgress,
    steps: List<RouteService.Step>,
    modifier: Modifier = Modifier,
) {
    val currentStep = steps.getOrNull(progress.currentStepIndex)
    val nextStep = steps.getOrNull(progress.currentStepIndex + 1)
    // Count down to the NEXT maneuver; the current step is the road we're on.
    val primary = nextStep ?: currentStep
    val primaryInstruction = primary?.navInstruction?.instructions.orEmpty()
    val primaryIcon = primary?.navInstruction?.maneuver?.iconContent()
    val distanceText = formatDistance(progress.distanceToNextManeuver)
    val shield = roadShieldFrom(primaryInstruction)
    // Lanes belong to the upcoming maneuver step.
    val lanes = primary?.lanes.orEmpty()

    Card(
        modifier = modifier
            .fillMaxWidth()
            .shadow(8.dp, MaterialTheme.shapes.extraLarge),
        shape = MaterialTheme.shapes.extraLarge,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer,
            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        ),
    ) {
        Column(Modifier.padding(Spacing.lg)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (primaryIcon != null) {
                    primaryIcon(Modifier.size(56.dp), MaterialTheme.colorScheme.onPrimaryContainer)
                    Spacer(Modifier.width(Spacing.lg))
                }
                Column(Modifier.weight(1f)) {
                    // The distance to the next turn is the one thing a driver reads at a
                    // glance, so it gets the emphasized headline role rather than a bold body.
                    Text(distanceText, style = MaterialTheme.typography.headlineMediumEmphasized)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (shield != null) {
                            RouteShield(shield)
                            Spacer(Modifier.width(Spacing.sm))
                        }
                        Text(
                            primaryInstruction,
                            style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier.weight(1f, fill = false),
                        )
                    }
                    val secondary = steps.getOrNull(progress.currentStepIndex + 2)
                    if (nextStep != null && secondary != null) {
                        Spacer(Modifier.height(Spacing.xs))
                        Text(
                            stringResource(R.string.then, secondary.navInstruction.instructions),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f),
                        )
                    }
                }
            }
            if (lanes.isNotEmpty()) {
                Spacer(Modifier.height(10.dp))
                LaneGuidance(
                    lanes = lanes,
                    modifier = Modifier.fillMaxWidth(),
                    activeColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    inactiveColor = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.3f),
                )
            }
        }
    }
}
