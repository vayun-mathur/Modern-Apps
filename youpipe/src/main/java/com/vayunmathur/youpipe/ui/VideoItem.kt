package com.vayunmathur.youpipe.ui
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.vayunmathur.library.image.ImageRequest
import com.vayunmathur.library.image.compose.AsyncImage
import com.vayunmathur.library.ui.DropdownMenu
import com.vayunmathur.library.ui.DropdownMenuItem
import com.vayunmathur.library.ui.IconButton
import com.vayunmathur.library.ui.IconMoreVert
import com.vayunmathur.library.ui.ListItem
import com.vayunmathur.library.ui.ListItemDefaults
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.Surface
import com.vayunmathur.library.ui.Text
import com.vayunmathur.library.ui.invisibleClickable
import com.vayunmathur.library.util.NavBackStack
import com.vayunmathur.library.util.sharedText
import com.vayunmathur.youpipe.R
import com.vayunmathur.youpipe.Route
import com.vayunmathur.youpipe.platform.VideoRowState
import com.vayunmathur.youpipe.platform.YouPipeViewModel
import com.vayunmathur.youpipe.network.decodeHtml


/**
 * ViewModel-backed binder in front of [VideoRow], for the screens that still hand a whole
 * ViewModel down to their list rows.
 */
@Composable
fun VideoItem(
    backStack: NavBackStack<Route>,
    youPipeViewModel: YouPipeViewModel,
    videoInfo: VideoInfo,
    showAuthor: Boolean,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    backupOnClick: Boolean = true,
    trailingContent: @Composable (() -> Unit)? = null,
    reason: String? = null,
    overflowActions: List<Pair<String, () -> Unit>> = emptyList(),
    /** Forwarded to [VideoRow]; see the parameter there for why it is per call site. */
    titleSharedKey: Any? = null,
    /** Stack text under the thumbnail for grid cells on expanded widths. */
    vertical: Boolean = false,
) {
    val context = LocalContext.current
    val historyFlow = remember(videoInfo.videoID) { youPipeViewModel.historyById(videoInfo.videoID) }
    val historyItem by historyFlow.collectAsState(initial = null)
    val timeWatched = historyItem?.progress ?: 0
    val percentWatched = if (videoInfo.duration > 0) timeWatched.toDouble() / videoInfo.duration.toDouble() else 0.0

    val deArrowEnabled by youPipeViewModel.deArrowEnabled.collectAsState()
    val deArrowCache by youPipeViewModel.deArrowCache.collectAsState()
    val deArrowData = if (deArrowEnabled) deArrowCache[videoInfo.videoID] else null

    val itemModifier = if (onClick != null) {
        modifier.clickable(onClick = onClick)
    } else if(backupOnClick) {
        modifier.invisibleClickable {
            backStack.add(Route.VideoPage(videoInfo.videoID))
        }
    } else {
        modifier
    }

    VideoRow(
        row = videoRowState(
            context = context,
            videoInfo = videoInfo,
            showAuthor = showAuthor,
            reason = reason,
            percentWatched = percentWatched.toFloat(),
            deArrowTitle = deArrowData?.title,
            deArrowThumbnailURL = deArrowData?.thumbnailUrl,
        ),
        modifier = itemModifier,
        trailingContent = trailingContent,
        overflowActions = overflowActions,
        titleSharedKey = titleSharedKey,
        vertical = vertical,
    )
}
