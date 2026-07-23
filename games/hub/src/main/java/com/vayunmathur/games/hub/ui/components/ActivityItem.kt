package com.vayunmathur.games.hub.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.vayunmathur.games.hub.data.entities.ActivityEventEntity
import com.vayunmathur.games.hub.util.formatRelativeTime
import com.vayunmathur.library.ui.Card
import com.vayunmathur.library.ui.Icon
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.Text

@Composable
fun ActivityItemCard(
    event: ActivityEventEntity,
    onGameClick: ((String) -> Unit)? = null,
    modifier: Modifier = Modifier
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
            Icon(
                painter = painterResource(id = iconForType(event.type)),
                contentDescription = event.type,
                modifier = Modifier.padding(top = 2.dp)
            )
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(text = event.title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                event.description?.let { Text(text = it, style = MaterialTheme.typography.bodySmall) }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    event.gameId?.let {
                        Text(text = it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                    }
                    Text(text = formatRelativeTime(event.timestamp), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

private fun iconForType(type: String): Int = when (type) {
    ActivityEventEntity.TYPE_ACHIEVEMENT_UNLOCKED -> android.R.drawable.btn_star_big_on
    ActivityEventEntity.TYPE_LEVEL_UP -> android.R.drawable.ic_menu_slideshow
    ActivityEventEntity.TYPE_GAME_REGISTERED -> android.R.drawable.ic_menu_add
    ActivityEventEntity.TYPE_SESSION_COMPLETED -> android.R.drawable.ic_media_play
    else -> android.R.drawable.ic_menu_info_details
}
