package com.vayunmathur.youpipe.tv

import android.content.Context
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.vayunmathur.library.image.ImageRequest
import com.vayunmathur.library.image.compose.AsyncImage
import com.vayunmathur.library.tv.TvButton
import com.vayunmathur.library.tv.TvEmptyState
import com.vayunmathur.library.tv.TvGrid
import com.vayunmathur.library.tv.TvInitialFocus
import com.vayunmathur.library.tv.TvLoading
import com.vayunmathur.library.tv.TvScreenHeader
import com.vayunmathur.library.util.NavBackStack
import com.vayunmathur.youpipe.R
import com.vayunmathur.youpipe.data.Subscription
import com.vayunmathur.youpipe.ui.ChannelInfo
import com.vayunmathur.youpipe.ui.countString
import com.vayunmathur.youpipe.util.YouPipeViewModel
import com.vayunmathur.youpipe.util.decodeHtml

/**
 * TV channel page: avatar header + subscribe button + 4-column video grid.
 *
 * Mirrors the phone `ChannelPage` binding (`loadChannel`, subscriptions for membership).
 * The header and subscribe button are stacked focus targets — not a scrolling collapse —
 * because a D-pad needs a stable place to land UP from the grid.
 */
@Composable
fun TvChannelPage(
    backStack: NavBackStack<TvRoute>,
    ypvm: YouPipeViewModel,
    channelID: String,
) {
    val channelState by ypvm.channelState.collectAsState()
    val videos = channelState.videos
    val channelInfo = channelState.info
    val subscriptions by ypvm.subscriptions.collectAsState()
    val history by ypvm.historyVideos.collectAsState()
    val historyById = remember(history) { history.associate { it.id to it.progress } }
    val context = LocalContext.current
    val subscribeFocus = remember { FocusRequester() }

    LaunchedEffect(channelID) {
        ypvm.loadChannel(channelID)
    }

    val rows = rememberTvVideoRows(videos, ypvm, historyById, showAuthor = false)

    TvInitialFocus(subscribeFocus, channelID)
    Column(Modifier.fillMaxSize()) {
        channelInfo?.let { info ->
            TvChannelHeader(
                info = info,
                subscriptions = subscriptions,
                subscribeFocus = subscribeFocus,
                context = context,
                ypvm = ypvm,
            )
        }
        TvChannelBody(
            channelInfo = channelInfo,
            rows = rows,
            onOpen = { backStack.playVideo(it) },
        )
    }
}

@Composable
private fun TvChannelHeader(
    info: ChannelInfo,
    subscriptions: List<Subscription>,
    subscribeFocus: FocusRequester,
    context: Context,
    ypvm: YouPipeViewModel,
) {
    TvScreenHeader(
        title = info.name.decodeHtml(),
        subtitle = stringResource(
            R.string.subscribers_count,
            countString(context, info.subscribers),
        ),
        actions = {
            val existing = subscriptions.firstOrNull { it.channelID == info.channelID }
            TvButton(
                text = stringResource(
                    if (existing == null) R.string.action_subscribe
                    else R.string.action_unsubscribe,
                ),
                onClick = {
                    if (existing == null) {
                        ypvm.upsertSubscription(
                            Subscription(
                                name = info.name,
                                channelID = info.channelID,
                                avatarURL = info.avatar,
                            ),
                        )
                    } else {
                        ypvm.deleteSubscription(existing)
                    }
                },
                primary = existing == null,
                modifier = Modifier.focusRequester(subscribeFocus),
            )
        },
    )
    TvChannelAvatar(info = info, context = context)
}

@Composable
private fun TvChannelAvatar(info: ChannelInfo, context: Context) {
    if (info.avatar.isNotEmpty()) {
        Row(
            Modifier.padding(start = 48.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AsyncImage(
                model = ImageRequest.Builder(context)
                    .data(info.avatar)
                    .memoryCacheKey("channel-avatar-${info.channelID}")
                    .build(),
                contentDescription = null,
                modifier = Modifier.tvCircularAvatar(),
                contentScale = ContentScale.Crop,
            )
        }
    }
}

@Composable
private fun TvChannelBody(
    channelInfo: ChannelInfo?,
    rows: List<TvVideoRow>,
    onOpen: (Long) -> Unit,
) {
    when {
        channelInfo == null -> TvLoading()
        rows.isEmpty() -> TvEmptyState(title = stringResource(R.string.empty_recommendations))
        else -> TvGrid(columns = TV_GRID_COLUMNS, modifier = Modifier.fillMaxSize()) {
            items(rows.size, key = { rows[it].row.videoID }) { index ->
                val item = rows[index]
                TvVideoCard(
                    item = item,
                    onOpen = { onOpen(item.row.videoID) },
                )
            }
        }
    }
}
