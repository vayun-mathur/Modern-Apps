package com.vayunmathur.youpipe.ui

import android.text.format.Formatter
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.vayunmathur.library.image.ImageRequest
import com.vayunmathur.library.image.compose.AsyncImage
import com.vayunmathur.library.ui.Card
import com.vayunmathur.library.ui.CircularProgressIndicator
import com.vayunmathur.library.ui.DropdownMenu
import com.vayunmathur.library.ui.IconArrowDropDown
import com.vayunmathur.library.ui.IconCheck
import com.vayunmathur.library.ui.IconClose
import com.vayunmathur.library.ui.IconDelete
import com.vayunmathur.library.ui.IconDownload
import com.vayunmathur.library.ui.IconSave
import com.vayunmathur.library.ui.IconShare
import com.vayunmathur.library.ui.IconButton
import com.vayunmathur.library.ui.ListItem
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.OutlinedButton
import com.vayunmathur.library.ui.SelectableDropdownMenuItem
import com.vayunmathur.library.ui.Text
import com.vayunmathur.library.ui.TextButton
import com.vayunmathur.library.ui.R as UiR
import com.vayunmathur.library.util.sharedText
import com.vayunmathur.youpipe.R
import com.vayunmathur.youpipe.platform.VideoDetailActions
import com.vayunmathur.youpipe.platform.VideoDetailUiState

@Composable
fun VideoDetails(
    state: VideoDetailUiState,
    actions: VideoDetailActions,
    titleSharedKey: Any? = null,
) {
    var isDownloadDialogVisible by remember { mutableStateOf(false) }

    if (isDownloadDialogVisible) {
        DownloadOptionsDialog(
            state = state,
            onDismiss = { isDownloadDialogVisible = false },
            onDownload = { videoUrl, audioUrl ->
                isDownloadDialogVisible = false
                actions.download(videoUrl, audioUrl)
            },
        )
    }

    Column {
        ListItem(modifier = Modifier, overlineContent = {}, supportingContent = {
            Text(state.byline)
        }, leadingContent = {
            // Block behind the avatar: the placeholder while it loads, and the whole of it
            // in a preview, where there is no network to load it from.
            Box(
                Modifier.size(32.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .clickable { actions.openChannel() }
            ) {
                if (state.authorThumbnailURL.isNotEmpty()) {
                    AsyncImage(
                        model = ImageRequest.Builder(LocalContext.current)
                            .data(state.authorThumbnailURL)
                            .memoryCacheKey("author-thumb-${state.authorURL}")
                            .build(),
                        contentDescription = null,
                        Modifier.fillMaxSize()
                    )
                }
            }
        }) {
            Text(
                state.title,
                style = MaterialTheme.typography.titleMedium,
                modifier = if (titleSharedKey == null) Modifier else Modifier.sharedText(titleSharedKey),
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = { actions.share() }) {
                IconShare()
            }
            IconButton(onClick = { actions.addToPlaylist() }) {
                IconSave()
            }
            val downloadProgress = state.downloadProgress
            if (downloadProgress != null) {
                CircularProgressIndicator(
                    progress = { downloadProgress },
                    modifier = Modifier.size(24.dp),
                    strokeWidth = 2.dp
                )
                IconButton(onClick = { actions.cancelDownload() }) {
                    IconClose()
                }
            } else if (!state.downloaded) {
                IconButton(onClick = {
                    isDownloadDialogVisible = true
                }) {
                    IconDownload()
                }
            } else {
                IconButton(onClick = { actions.deleteDownload() }) {
                    IconDelete(tint = MaterialTheme.colorScheme.error)
                }
            }
        }
    }
}

@Composable
private fun DownloadOptionsDialog(
    state: VideoDetailUiState,
    onDismiss: () -> Unit,
    onDownload: (videoUrl: String, audioUrl: String?) -> Unit,
) {
    val videoStreams = state.videoStreams
    val audioStreams = state.audioStreams
    var selectedVideoStream by remember { mutableStateOf(videoStreams.maxByOrNull { it.height } ?: videoStreams.first()) }
    // Always highest quality opus for selected language in download as well
    var selectedAudioStream by remember { mutableStateOf(audioStreams.maxByOrNull { it.bitrate }) }

    val languageEntriesDownload = remember(audioStreams) {
        audioStreams.map { it.language to (it.displayName ?: it.language) }
            .distinctBy { it.first }
            .sortedBy { it.first }
    }
    val languages = languageEntriesDownload.map { it.first }
    var selectedLanguage by remember { mutableStateOf(selectedAudioStream?.language ?: languages.firstOrNull() ?: "Default") }

    Dialog(onDismissRequest = onDismiss) {
        Card {
            Column(Modifier.padding(16.dp)) {
                Text(stringResource(R.string.download_options), style = MaterialTheme.typography.titleLarge)
                Spacer(Modifier.height(16.dp))

                Text(stringResource(R.string.resolution), style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(8.dp))
                VideoQualityDropdown(
                    streams = videoStreams,
                    selected = selectedVideoStream,
                    onSelect = { selectedVideoStream = it },
                    modifier = Modifier.fillMaxWidth(),
                )

                if (languages.size > 1) {
                    Spacer(Modifier.height(16.dp))
                    Text(stringResource(R.string.language), style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(8.dp))
                    AudioLanguageDropdown(
                        options = languageEntriesDownload,
                        selectedCode = selectedLanguage,
                        onSelect = { code ->
                            selectedLanguage = code
                            selectedAudioStream = audioStreams.filter { it.language == code }.maxByOrNull { it.bitrate }
                        },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }

                Spacer(Modifier.height(24.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = onDismiss) { Text(stringResource(UiR.string.cancel)) }
                    TextButton(onClick = {
                        onDownload(selectedVideoStream.url, selectedAudioStream?.url)
                    }) { Text(stringResource(R.string.download)) }
                }
            }
        }
    }
}

/**
 * The download resolution picker, built from [OutlinedButton] + [DropdownMenu] rather than the
 * library's `ExposedDropdownMenu` wrapper, which currently recurses into itself.
 */
@Composable
private fun VideoQualityDropdown(
    streams: List<VideoStream>,
    selected: VideoStream,
    onSelect: (VideoStream) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    var expanded by remember { mutableStateOf(false) }
    Box(modifier) {
        OutlinedButton(onClick = { expanded = true }, modifier = Modifier.fillMaxWidth()) {
            Text(
                "${selected.quality} - ${Formatter.formatShortFileSize(context, selected.size)}",
                modifier = Modifier.weight(1f),
                textAlign = TextAlign.Start,
                maxLines = 1,
            )
            IconArrowDropDown()
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            streams.forEach { stream ->
                SelectableDropdownMenuItem(
                    selected = stream == selected,
                    onClick = {
                        onSelect(stream)
                        expanded = false
                    },
                    text = { Text("${stream.quality} (${getVideoCodecName(stream.codec)}) - ${Formatter.formatShortFileSize(context, stream.size)}") },
                    selectedLeadingIcon = { IconCheck() },
                )
            }
        }
    }
}

/**
 * The download audio-language picker, built from [OutlinedButton] + [DropdownMenu] rather than
 * the library's `ExposedDropdownMenu` wrapper, which currently recurses into itself.
 */
@Composable
private fun AudioLanguageDropdown(
    options: List<Pair<String, String>>,
    selectedCode: String,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }
    Box(modifier) {
        OutlinedButton(onClick = { expanded = true }, modifier = Modifier.fillMaxWidth()) {
            Text(
                options.find { it.first == selectedCode }?.second ?: selectedCode,
                modifier = Modifier.weight(1f),
                textAlign = TextAlign.Start,
                maxLines = 1,
            )
            IconArrowDropDown()
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEach { (code, display) ->
                SelectableDropdownMenuItem(
                    selected = code == selectedCode,
                    onClick = {
                        onSelect(code)
                        expanded = false
                    },
                    text = { Text(display) },
                    selectedLeadingIcon = { IconCheck() },
                )
            }
        }
    }
}
