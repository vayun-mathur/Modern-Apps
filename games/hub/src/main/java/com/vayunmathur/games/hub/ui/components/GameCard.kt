package com.vayunmathur.games.hub.ui.components

import android.graphics.drawable.Drawable
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import com.vayunmathur.games.hub.data.entities.HubGameEntity
import com.vayunmathur.games.hub.util.GameIconResolver
import com.vayunmathur.games.hub.util.formatPlaytime
import com.vayunmathur.library.ui.Card
import com.vayunmathur.library.ui.CardDefaults
import com.vayunmathur.library.ui.IconButton
import com.vayunmathur.library.ui.IconDownload
import com.vayunmathur.library.ui.IconPlay
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.Text
import androidx.compose.ui.res.stringResource
import com.vayunmathur.games.hub.R

@Composable
fun GameCard(
    game: HubGameEntity,
    isInstalled: Boolean,
    modifier: Modifier = Modifier,
    achievementProgress: Pair<Int, Int>? = null,
    /** (current, longest) daily-puzzle streak, or null for a game that has none. */
    dailyStreak: Pair<Int, Int>? = null,
    iconDrawable: Drawable? = null,
    onClick: () -> Unit,
    onPlay: () -> Unit
) {
    val context = LocalContext.current
    val resolvedIcon = iconDrawable ?: remember(game.packageName) {
        GameIconResolver.resolveAppIcon(context, game.packageName)
    }

    Card(
        onClick = onClick,
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (isInstalled) MaterialTheme.colorScheme.surfaceVariant
            else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)
        )
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Box(
                modifier = Modifier.size(48.dp).clip(RoundedCornerShape(12.dp)),
                contentAlignment = Alignment.Center
            ) {
                if (resolvedIcon != null) {
                    val bmp = remember(resolvedIcon) {
                        try { resolvedIcon.toBitmap(96, 96) } catch (_: Exception) { null }
                    }
                    if (bmp != null) {
                        Image(bitmap = bmp.asImageBitmap(), contentDescription = game.displayName, modifier = Modifier.size(48.dp).clip(RoundedCornerShape(12.dp)))
                    } else {
                        Text(text = game.displayName.firstOrNull()?.uppercase() ?: "?", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    }
                } else {
                    Text(text = game.displayName.firstOrNull()?.uppercase() ?: "?", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                }
            }

            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(text = game.displayName, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Text(text = if (isInstalled) stringResource(R.string.installed) else stringResource(R.string.not_installed), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (game.totalPlaytimeMs > 0) {
                    Text(stringResource(R.string.playtime_sessions, formatPlaytime(game.totalPlaytimeMs), game.totalSessions), style = MaterialTheme.typography.labelSmall)
                }
                achievementProgress?.let { (unlocked, total) ->
                    if (total > 0) {
                        Text(stringResource(R.string.achievements_2, unlocked, total), style = MaterialTheme.typography.labelSmall)
                    }
                }
                // The streak line always occupies one line of labelSmall text so every
                // tile in the dashboard carousel keeps the same height, whether or
                // not the game has an active streak (#698). An inactive streak is an
                // invisible, semantics-hidden placeholder rather than missing content.
                val streakText = dailyStreak?.let { (current, longest) ->
                    if (current > 0) stringResource(R.string.daily_streak_days, current, longest) else null
                }
                Text(
                    streakText ?: stringResource(R.string.daily_streak_days, 0, 0),
                    style = MaterialTheme.typography.labelSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = if (streakText != null) Modifier
                    else Modifier.alpha(0f).clearAndSetSemantics { }
                )
            }

            // No achievement ring here: the "Achievements: x/y" line above already says it,
            // and a small trackless circular indicator read as a loading spinner (#ux-audit).
            // Not installed (never, or uninstalled since): offer Install, which opens the store
            // listing, rather than a Play button that cannot work. Both speak the game's name.
            val actionLabel = if (isInstalled) stringResource(R.string.play_1, game.displayName)
            else stringResource(R.string.install_game, game.displayName)
            IconButton(onClick = onPlay, modifier = Modifier.semantics { contentDescription = actionLabel }) {
                Box(Modifier.clearAndSetSemantics { }) {
                    if (isInstalled) IconPlay() else IconDownload()
                }
            }
        }
    }
}
