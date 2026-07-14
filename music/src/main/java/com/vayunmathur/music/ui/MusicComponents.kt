package com.vayunmathur.music.ui

import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.vayunmathur.library.ui.IconPlay
import com.vayunmathur.music.R
import com.vayunmathur.music.util.AlbumArt

/** The Play + Shuffle button pair shared by the album/artist/playlist detail screens. */
@Composable
fun PlayShuffleRow(onPlay: () -> Unit, onShuffle: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 24.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Button(
            onClick = onPlay,
            modifier = Modifier.weight(1f),
            colors = ButtonDefaults.buttonColors(containerColor = Color.White.copy(alpha = 0.15f)),
            shape = RoundedCornerShape(50.dp),
            contentPadding = PaddingValues(vertical = 12.dp),
        ) {
            IconPlay(tint = Color.White)
            Spacer(modifier = Modifier.width(8.dp))
            Text(stringResource(R.string.label_play), color = Color.White)
        }

        Button(
            onClick = onShuffle,
            modifier = Modifier.weight(1f),
            colors = ButtonDefaults.buttonColors(containerColor = Color.White),
            shape = RoundedCornerShape(50.dp),
            contentPadding = PaddingValues(vertical = 12.dp),
        ) {
            Icon(painterResource(R.drawable.ic_shuffle), contentDescription = null, tint = Color.Black)
            Spacer(modifier = Modifier.width(8.dp))
            Text(stringResource(R.string.label_shuffle), color = Color.Black)
        }
    }
}

/**
 * A track row that highlights the currently-playing song (title color/weight + container tint) and
 * shows album art. [leading] (rendered before the art) and [trailing] let each screen supply the
 * bits that differ (track numbers, remove/add-to-playlist actions, etc.).
 */
@Composable
fun TrackListItem(
    title: String,
    isPlaying: Boolean,
    artUri: Uri,
    onClick: () -> Unit,
    artSize: Dp = 48.dp,
    leading: (@Composable RowScope.() -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    ListItem(
        content = {
            Text(
                text = title,
                color = if (isPlaying) MaterialTheme.colorScheme.primary else Color.Unspecified,
                fontWeight = if (isPlaying) FontWeight.Bold else FontWeight.Normal,
            )
        },
        modifier = Modifier
            .clip(RoundedCornerShape(12.dp))
            .background(if (isPlaying) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent)
            .clickable(onClick = onClick),
        trailingContent = trailing,
        leadingContent = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                leading?.invoke(this)
                AlbumArt(artUri, Modifier.size(artSize))
            }
        },
    )
}
