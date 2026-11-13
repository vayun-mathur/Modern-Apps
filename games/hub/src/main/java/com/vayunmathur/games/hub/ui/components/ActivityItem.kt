package com.vayunmathur.games.hub.ui.components

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.vayunmathur.games.hub.R
import com.vayunmathur.games.hub.data.entities.ActivityEventEntity
import com.vayunmathur.games.hub.domain.KnownGames
import com.vayunmathur.games.hub.util.formatRelativeTime
import com.vayunmathur.library.ui.Card
import com.vayunmathur.library.ui.IconAdd
import com.vayunmathur.library.ui.IconEmojiEvents
import com.vayunmathur.library.ui.IconInfo
import com.vayunmathur.library.ui.IconPlay
import com.vayunmathur.library.ui.IconPlayCircle
import com.vayunmathur.library.ui.IconStar
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.Text

@Composable
fun ActivityItemCard(
    event: ActivityEventEntity,
    modifier: Modifier = Modifier,
    onGameClick: ((String) -> Unit)? = null,
    /** gameId -> registered display name; ids missing here resolve through [KnownGames]. */
    gameNames: Map<String, String> = emptyMap(),
) {
    Card(
        onClick = { event.gameId?.let { onGameClick?.invoke(it) } },
        modifier = modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.Top,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // The icon speaks the event kind ("Achievement unlocked"), not the stored constant.
            val typeLabel = stringResource(activityTypeLabel(event.type))
            Box(Modifier.padding(top = 2.dp).clearAndSetSemantics { contentDescription = typeLabel }) {
                ActivityTypeIcon(event.type)
            }
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(text = displayTitle(event, gameNames), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                event.description?.let { Text(text = it, style = MaterialTheme.typography.bodySmall) }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    event.gameId?.let {
                        Text(text = KnownGames.displayName(it, gameNames[it]), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                    }
                    Text(text = formatRelativeTime(event.timestamp), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

/** The spoken name of an activity [type]; unknown types read as plain "Activity". */
@StringRes
internal fun activityTypeLabel(type: String): Int = when (type) {
    ActivityEventEntity.TYPE_ACHIEVEMENT_UNLOCKED -> R.string.activity_type_achievement
    ActivityEventEntity.TYPE_LEVEL_UP -> R.string.activity_type_level_up
    ActivityEventEntity.TYPE_GAME_REGISTERED -> R.string.activity_type_game_added
    ActivityEventEntity.TYPE_SESSION_COMPLETED -> R.string.activity_type_session
    ActivityEventEntity.TYPE_FIRST_PLAY -> R.string.activity_type_first_play
    ActivityEventEntity.TYPE_GAME_LAUNCHED -> R.string.activity_type_launched
    else -> R.string.activity_type_other
}

/** Icons.kt wrappers only - no framework drawables, no raw Icon(). */
@Composable
private fun ActivityTypeIcon(type: String) = when (type) {
    ActivityEventEntity.TYPE_ACHIEVEMENT_UNLOCKED -> IconEmojiEvents()
    ActivityEventEntity.TYPE_LEVEL_UP -> IconStar()
    ActivityEventEntity.TYPE_GAME_REGISTERED -> IconAdd()
    ActivityEventEntity.TYPE_SESSION_COMPLETED -> IconPlay()
    ActivityEventEntity.TYPE_FIRST_PLAY, ActivityEventEntity.TYPE_GAME_LAUNCHED -> IconPlayCircle()
    else -> IconInfo()
}

/**
 * Session rows written before game names were resolved stored "Played <gameId>"; show those
 * with the game's name so old feed entries do not leak the id either.
 */
private fun displayTitle(event: ActivityEventEntity, gameNames: Map<String, String>): String {
    val id = event.gameId ?: return event.title
    val prefix = ActivityEventEntity.SESSION_TITLE_PREFIX
    return if (event.type == ActivityEventEntity.TYPE_SESSION_COMPLETED && event.title == "$prefix$id") {
        prefix + KnownGames.displayName(id, gameNames[id])
    } else {
        event.title
    }
}
