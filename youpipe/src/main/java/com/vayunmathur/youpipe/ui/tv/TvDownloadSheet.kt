package com.vayunmathur.youpipe.ui.tv

import android.text.format.Formatter
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.vayunmathur.library.tv.TvButton
import com.vayunmathur.library.tv.TvDialog
import com.vayunmathur.library.tv.TvListRow
import com.vayunmathur.library.ui.IconCheck
import com.vayunmathur.library.ui.R as UiR
import com.vayunmathur.youpipe.R
import com.vayunmathur.youpipe.ui.AudioStream
import com.vayunmathur.youpipe.ui.VideoStream
import com.vayunmathur.youpipe.ui.getVideoCodecName

/**
 * D-pad download picker: quality (with size + codec) then audio language, then confirm.
 * Replaces the TV one-tap-best with the phone `ui/VideoDetails.kt` DownloadOptionsDialog
 * choices — quality+size+codec, language, best-opus default — as a [TvDialog] of
 * focusable rows.
 */
@Composable
fun TvDownloadSheet(
    videoStreams: List<VideoStream>,
    audioStreams: List<AudioStream>,
    onConfirm: (videoUrl: String, audioUrl: String?) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    var selectedVideoStream by remember(videoStreams) {
        mutableStateOf(videoStreams.maxByOrNull { it.height } ?: videoStreams.first())
    }
    // Always highest quality opus for selected language in download as well.
    var selectedAudioStream by remember(audioStreams) {
        mutableStateOf(audioStreams.maxByOrNull { it.bitrate })
    }
    val languageEntries = remember(audioStreams) {
        audioStreams.map { it.language to (it.displayName ?: it.language) }
            .distinctBy { it.first }
            .sortedBy { it.first }
    }
    var selectedLanguage by remember(audioStreams) {
        mutableStateOf(selectedAudioStream?.language ?: languageEntries.firstOrNull()?.first.orEmpty())
    }
    TvDialog(
        title = stringResource(R.string.download_options),
        onDismissRequest = onDismiss,
        modifier = modifier,
        buttons = {
            TvButton(
                text = stringResource(R.string.download),
                onClick = { onConfirm(selectedVideoStream.url, selectedAudioStream?.url) },
                primary = true,
            )
            TvButton(
                text = stringResource(UiR.string.cancel),
                onClick = onDismiss,
            )
        },
        content = {
            TvDownloadOptionsList(
                videoStreams = videoStreams,
                selectedVideoStream = selectedVideoStream,
                onSelectVideo = { stream -> selectedVideoStream = stream },
                languageEntries = languageEntries,
                selectedLanguage = selectedLanguage,
                onSelectLanguage = { code ->
                    selectedLanguage = code
                    selectedAudioStream = audioStreams
                        .filter { stream -> stream.language == code }
                        .maxByOrNull { stream -> stream.bitrate }
                },
            )
        },
    )
}

/** Quality + language rows of the download dialog. Split for FileLength. */
@Composable
private fun TvDownloadOptionsList(
    videoStreams: List<VideoStream>,
    selectedVideoStream: VideoStream,
    onSelectVideo: (VideoStream) -> Unit,
    languageEntries: List<Pair<String, String>>,
    selectedLanguage: String,
    onSelectLanguage: (String) -> Unit,
) {
    val context = LocalContext.current
    Column(Modifier.fillMaxWidth()) {
        androidx.tv.material3.Text(
            text = stringResource(R.string.resolution),
            style = androidx.tv.material3.MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(vertical = 4.dp),
        )
        videoStreams.forEach { stream ->
            TvListRow(
                headline = stream.quality,
                supporting = "${getVideoCodecName(stream.codec)} - " +
                    Formatter.formatShortFileSize(context, stream.size),
                trailing = if (stream == selectedVideoStream) {
                    { IconCheck() }
                } else {
                    null
                },
                selected = stream == selectedVideoStream,
                onClick = { onSelectVideo(stream) },
            )
        }
        if (languageEntries.size > 1) {
            androidx.tv.material3.Text(
                text = stringResource(R.string.language),
                style = androidx.tv.material3.MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(top = 8.dp, bottom = 4.dp),
            )
            languageEntries.forEach { (code, display) ->
                TvListRow(
                    headline = display,
                    trailing = if (code == selectedLanguage) {
                        { IconCheck() }
                    } else {
                        null
                    },
                    selected = code == selectedLanguage,
                    onClick = { onSelectLanguage(code) },
                )
            }
        }
    }
}
