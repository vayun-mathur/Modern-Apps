package com.vayunmathur.games.hub.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.vayunmathur.games.hub.data.dao.AchievementWithProgress
import com.vayunmathur.games.hub.domain.KnownGames
import com.vayunmathur.library.ui.Card
import com.vayunmathur.library.ui.CardDefaults
import com.vayunmathur.library.ui.IconLock
import com.vayunmathur.library.ui.IconStar
import com.vayunmathur.library.ui.LinearProgressIndicator
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.Text
import androidx.compose.ui.res.stringResource
import com.vayunmathur.games.hub.R

@Composable
fun AchievementRow(
    item: AchievementWithProgress,
    modifier: Modifier = Modifier,
    showGameTag: Boolean = true,
    /** gameId -> registered display name; ids missing here resolve through [KnownGames]. */
    gameNames: Map<String, String> = emptyMap(),
) {
    val isLocked = !item.isUnlocked

    Card(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (item.isUnlocked) MaterialTheme.colorScheme.secondaryContainer
            else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)
        )
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Locked vs unlocked must not rest on colour/alpha alone: a locked row gets a lock
            // glyph and a visible "Locked" label (which TalkBack reads, so the icon stays silent);
            // an unlocked row keeps the star and speaks "Unlocked".
            val unlockedLabel = stringResource(R.string.achievement_unlocked)
            Box(
                Modifier.size(40.dp).clearAndSetSemantics {
                    if (item.isUnlocked) contentDescription = unlockedLabel
                },
            ) {
                if (item.isUnlocked) {
                    IconStar(modifier = Modifier.size(40.dp), tint = MaterialTheme.colorScheme.onSecondaryContainer)
                } else {
                    IconLock(modifier = Modifier.size(40.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Column(
                modifier = Modifier
                    .padding(start = 12.dp)
                    .weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = if (item.isSecret && isLocked) "???" else item.name,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                    if (isLocked) {
                        Text(
                            text = stringResource(R.string.achievement_locked),
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Text(
                        text = stringResource(R.string.xp_1, item.xpReward),
                        style = MaterialTheme.typography.labelSmall,
                        color = if (item.isUnlocked) MaterialTheme.colorScheme.onSecondaryContainer
                                else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Text(
                    text = if (item.isSecret && isLocked) stringResource(R.string.keep_playing_to_reveal) else item.description,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 2
                )
                if (showGameTag) {
                    Text(
                        text = KnownGames.displayName(item.gameId, gameNames[item.gameId]),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
                if (item.targetProgress > 1) {
                    val displayProgress = if (item.isUnlocked) item.targetProgress else item.progress
                    val ratio = (displayProgress.toFloat() / item.targetProgress.toFloat()).coerceIn(0f, 1f)
                    LinearProgressIndicator(
                        progress = { ratio },
                        modifier = Modifier.fillMaxWidth().padding(top = 4.dp)
                    )
                    Text(
                        text = if (item.isUnlocked) stringResource(R.string.completed) else "$displayProgress / ${item.targetProgress}",
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.align(Alignment.End)
                    )
                }
            }
        }
    }
}
