package com.vayunmathur.uniflix.ui.tv

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.vayunmathur.library.tv.TvChip
import com.vayunmathur.library.tv.TvIconButton
import com.vayunmathur.library.tv.TvInitialFocus
import com.vayunmathur.library.tv.TvSeekRow
import com.vayunmathur.library.ui.IconBack
import com.vayunmathur.library.ui.IconPause
import com.vayunmathur.library.ui.IconPlay
import com.vayunmathur.library.ui.IconRotateLeft
import com.vayunmathur.library.ui.IconRotateRight
import com.vayunmathur.uniflix.ui.SKIP_MS
import com.vayunmathur.uniflix.ui.WatchPlayerState
import com.vayunmathur.uniflix.ui.formatWatchTime

/**
 * The TV player overlay: back + title + server chip on top, seek row and
 * transport below. Picker dialogs live in TvWatchPickers.
 */
@Composable
internal fun TvWatchOverlay(
    title: String,
    serverLabel: String,
    showServerPicker: Boolean,
    onOpenServerPicker: () -> Unit,
    playerState: WatchPlayerState,
    subtitleLabel: String,
    showSubtitlePicker: Boolean,
    onOpenSubtitlePicker: () -> Unit,
    qualityLabel: String,
    showQualityPicker: Boolean,
    onOpenQualityPicker: () -> Unit,
    playFocus: FocusRequester,
    onBack: () -> Unit,
) {
    val duration = playerState.durationMs
    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.4f))) {
        Row(
            Modifier.align(Alignment.TopCenter).fillMaxWidth()
                .padding(horizontal = 48.dp, vertical = 28.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TvIconButton(onClick = onBack, icon = { IconBack() })
            Text(
                text = title,
                color = Color.White,
                style = MaterialTheme.typography.titleLarge,
                maxLines = 1,
                modifier = Modifier.weight(1f),
                overflow = TextOverflow.Ellipsis,
            )
            if (showServerPicker) {
                TvChip(label = serverLabel, selected = false, onClick = onOpenServerPicker)
            }
        }
        Column(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                .padding(horizontal = 48.dp, vertical = 28.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            TvSeekRow(
                elapsed = formatWatchTime(playerState.positionMs),
                total = formatWatchTime(duration),
                progress = if (duration > 0) playerState.positionMs.toFloat() / duration else 0f,
                buffered = if (duration > 0) playerState.bufferedMs.toFloat() / duration else 0f,
                active = true,
            )
            TvInitialFocus(playFocus)
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TvIconButton(onClick = { playerState.skipBy(-SKIP_MS) }, icon = { IconRotateLeft() })
                TvIconButton(
                    onClick = { playerState.togglePlay() },
                    icon = { if (playerState.isPlaying) IconPause() else IconPlay() },
                    modifier = Modifier.focusRequester(playFocus).padding(horizontal = 12.dp),
                    size = 64.dp,
                )
                TvIconButton(onClick = { playerState.skipBy(SKIP_MS) }, icon = { IconRotateRight() })
            }
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
            ) {
                if (showSubtitlePicker) {
                    TvChip(label = subtitleLabel, selected = false, onClick = onOpenSubtitlePicker)
                }
                if (showQualityPicker) {
                    TvChip(label = qualityLabel, selected = false, onClick = onOpenQualityPicker)
                }
            }
        }
    }
}
