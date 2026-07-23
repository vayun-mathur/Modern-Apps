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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import com.vayunmathur.games.hub.data.entities.HubGameEntity
import com.vayunmathur.games.hub.util.GameIconResolver
import com.vayunmathur.games.hub.util.formatPlaytime
import com.vayunmathur.library.ui.Card
import com.vayunmathur.library.ui.CardDefaults
import com.vayunmathur.library.ui.CircularProgressIndicator
import com.vayunmathur.library.ui.IconButton
import com.vayunmathur.library.ui.IconPlay
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.Text

@Composable
fun GameCard(
    game: HubGameEntity,
    isInstalled: Boolean,
    achievementProgress: Pair<Int, Int>? = null,
    iconDrawable: Drawable? = null,
    onClick: () -> Unit,
    onPlay: () -> Unit,
    modifier: Modifier = Modifier
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
                Text(text = if (isInstalled) "Installed" else "Not installed", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (game.totalPlaytimeMs > 0) {
                    Text(text = "Playtime: ${formatPlaytime(game.totalPlaytimeMs)} • ${game.totalSessions} sessions", style = MaterialTheme.typography.labelSmall)
                }
                achievementProgress?.let { (unlocked, total) ->
                    if (total > 0) {
                        Text(text = "Achievements: $unlocked/$total", style = MaterialTheme.typography.labelSmall)
                    }
                }
            }

            if (achievementProgress != null && achievementProgress.second > 0) {
                val (unlocked, total) = achievementProgress
                val denom = total.toFloat().coerceAtLeast(1f)
                val frac = (unlocked.toFloat() / denom).coerceIn(0f, 1f)
                CircularProgressIndicator(progress = { frac }, modifier = Modifier.size(32.dp))
            }

            IconButton(onClick = onPlay) {
                IconPlay()
            }
        }
    }
}
