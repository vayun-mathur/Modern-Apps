package com.vayunmathur.library.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalAccessibilityManager
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import com.vayunmathur.library.ui.R
import com.vayunmathur.library.util.Achievement
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first

/**
 * The toast announcing an unlocked achievement, sliding in from the top of the screen.
 *
 * It stays about four seconds — longer when the user has asked Android to give timed messages more
 * time — and goes early on a tap or an upward swipe. Only the card itself takes touches: the rest of
 * the screen keeps working under and around it.
 *
 * It keeps clear of the status bar, display cutouts and rounded corners through [windowInsets] (the top
 * and sides of safe drawing by default), plus a small margin. Insets an ancestor has already consumed
 * are not applied twice.
 *
 * It is a polite live region, so TalkBack announces the unlock when it appears.
 *
 * [onDismiss] runs once the exit animation has finished, which is when the caller should move on to the
 * next queued unlock. Each [achievement] gets a fresh toast, so a queue that advances in place shows
 * the next one from the start.
 */
@Composable
fun AchievementNotification(
    achievement: Achievement,
    onDismiss: () -> Unit,
    windowInsets: WindowInsets = WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal),
) {
    key(achievement) { AchievementToast(achievement, onDismiss, windowInsets) }
}

@Composable
private fun AchievementToast(achievement: Achievement, onDismiss: () -> Unit, windowInsets: WindowInsets) {
    // Starts hidden with a visible target, so it slides in on first composition.
    val visibleState = remember { MutableTransitionState(false).apply { targetState = true } }
    val currentOnDismiss by rememberUpdatedState(onDismiss)
    val accessibilityManager = LocalAccessibilityManager.current
    val hide = { visibleState.targetState = false }

    LaunchedEffect(Unit) {
        val shownFor = accessibilityManager?.calculateRecommendedTimeoutMillis(
            DISPLAY_MILLIS,
            containsIcons = true,
            containsText = true,
            containsControls = true,
        ) ?: DISPLAY_MILLIS
        delay(shownFor)
        hide()
    }
    // Tell the caller only once the exit transition has fully played out.
    LaunchedEffect(Unit) {
        snapshotFlow { visibleState.isIdle && !visibleState.currentState }.first { it }
        currentOnDismiss()
    }

    AnimatedVisibility(
        visibleState = visibleState,
        enter = slideInVertically { -it } + fadeIn(),
        exit = slideOutVertically { -it } + fadeOut(),
        modifier = Modifier
            .windowInsetsPadding(windowInsets)
            .padding(16.dp)
    ) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                // TalkBack reads the merged card ("Achievement Unlocked!, <name>, <description>") when it
                // appears, without interrupting whatever it is already saying.
                .semantics { liveRegion = LiveRegionMode.Polite }
                // Tap to dismiss; swipe up past the threshold to dismiss too. Both play the same exit
                // animation as the timeout.
                .clickable(onClickLabel = stringResource(R.string.dismiss), onClick = hide)
                .pointerInput(Unit) {
                    val threshold = SWIPE_THRESHOLD.toPx()
                    var totalDrag = 0f
                    detectVerticalDragGestures(
                        onDragEnd = { totalDrag = 0f },
                        onDragCancel = { totalDrag = 0f },
                    ) { change, dragAmount ->
                        change.consume()
                        totalDrag += dragAmount
                        if (totalDrag <= -threshold) hide()
                    }
                },
            shape = MaterialTheme.shapes.largeIncreased,
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.primaryContainer,
                contentColor = MaterialTheme.colorScheme.onPrimaryContainer
            ),
            elevation = CardDefaults.cardElevation(defaultElevation = 8.dp)
        ) {
            Row(
                modifier = Modifier.padding(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    painter = painterResource(id = android.R.drawable.btn_star_big_on), // Placeholder icon
                    contentDescription = null,
                    modifier = Modifier.size(40.dp),
                    tint = Color(0xFFFFD700)
                )
                Column(
                    modifier = Modifier
                        .padding(start = 16.dp)
                        .weight(1f)
                ) {
                    Text(
                        text = stringResource(R.string.achievement_unlocked),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f)
                    )
                    Text(
                        text = achievement.name,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = achievement.description,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        }
    }
}

/** Long enough to read a name and a one-line description; the old eight seconds overstayed. */
private const val DISPLAY_MILLIS = 4_000L

private val SWIPE_THRESHOLD = 48.dp
