package com.vayunmathur.games.hub.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.vayunmathur.games.hub.R
import com.vayunmathur.games.hub.data.YuuUiModel
import com.vayunmathur.games.hub.data.entities.encode
import com.vayunmathur.games.hub.ui.components.YuuPlaceholder
import com.vayunmathur.games.hub.ui.yuu.YuuListActions
import com.vayunmathur.games.hub.ui.yuu.YuuListUiState
import com.vayunmathur.games.hub.ui.yuu.YuuSurfaceIds
import com.vayunmathur.library.avatar.components.YuuAvatar3D
import com.vayunmathur.library.ui.AppScaffold
import com.vayunmathur.library.ui.Card
import com.vayunmathur.library.ui.DropdownMenu
import com.vayunmathur.library.ui.DropdownMenuItem
import com.vayunmathur.library.ui.FloatingActionButton
import com.vayunmathur.library.ui.IconAdd
import com.vayunmathur.library.ui.IconMoreVert
import com.vayunmathur.library.ui.IconButton
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.Text
import com.vayunmathur.library.ui.R as UiR

/**
 * The Yuu plaza: grid of named avatars with create / edit / duplicate / delete /
 * set-as-profile. Stateless — the editor itself lives in [YuuEditorScreen].
 */
@Composable
fun YuuScreen(
    state: YuuListUiState,
    actions: YuuListActions,
    modifier: Modifier = Modifier,
) {
    AppScaffold(
        title = { Text(stringResource(R.string.tab_yuu), fontWeight = FontWeight.Bold) },
        floatingActionButton = {
            FloatingActionButton(onClick = actions::onCreate) { IconAdd() }
        },
    ) { padding ->
        if (state.yuus.isEmpty()) {
            Box(
                modifier = modifier.fillMaxSize().padding(padding).padding(32.dp),
                contentAlignment = Alignment.Center,
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        text = stringResource(R.string.yuu_empty_title),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        textAlign = TextAlign.Center,
                    )
                    Text(
                        text = stringResource(R.string.yuu_empty_body),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                    )
                }
            }
        } else {
            LazyVerticalGrid(
                columns = GridCells.Adaptive(120.dp),
                modifier = modifier.fillMaxSize().padding(padding),
                contentPadding = PaddingValues(16.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                items(state.yuus, key = { it.id }) { yuu ->
                    YuuCard(
                        yuu = yuu,
                        isProfile = yuu.id == state.profileYuuId,
                        actions = actions,
                    )
                }
            }
        }
    }
}

@Composable
private fun YuuCard(
    yuu: YuuUiModel,
    isProfile: Boolean,
    actions: YuuListActions,
    modifier: Modifier = Modifier,
) {
    var menuOpen by remember { mutableStateOf(false) }
    Card(onClick = { actions.onEdit(yuu) }, modifier = modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(12.dp).fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Box(contentAlignment = Alignment.TopEnd) {
                // Live 3D cell with a stable id (keyed by Yuu id, not position)
                // so config/pose never bleeds across recycled cells. Over the
                // live-surface cap the cell shows the placeholder.
                val surfaceId = YuuSurfaceIds.forYuu(yuu.id)
                if (YuuSurfaceIds.isLive(surfaceId)) {
                    YuuAvatar3D(
                        configJson = yuu.config.encode(),
                        poseId = yuu.config.poseId,
                        surfaceId = surfaceId,
                        modifier = Modifier.fillMaxWidth().aspectRatio(1f),
                        fallback = {
                            YuuPlaceholder(
                                name = yuu.name,
                                modifier = Modifier.fillMaxSize(),
                            )
                        },
                    )
                } else {
                    YuuPlaceholder(
                        name = yuu.name,
                        modifier = Modifier.fillMaxWidth().aspectRatio(1f),
                    )
                }
                IconButton(onClick = { menuOpen = true }) { IconMoreVert() }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.yuu_edit)) },
                        onClick = { menuOpen = false; actions.onEdit(yuu) },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.duplicate)) },
                        onClick = { menuOpen = false; actions.onDuplicate(yuu) },
                    )
                    if (isProfile) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.remove_profile_avatar)) },
                            onClick = { menuOpen = false; actions.onClearProfile() },
                        )
                    } else {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.set_as_profile_avatar)) },
                            onClick = { menuOpen = false; actions.onSetAsProfile(yuu) },
                        )
                    }
                    DropdownMenuItem(
                        text = { Text(stringResource(UiR.string.delete)) },
                        onClick = { menuOpen = false; actions.onDelete(yuu) },
                    )
                }
            }
            Text(
                text = yuu.name,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (isProfile) {
                Text(
                    text = stringResource(R.string.profile_avatar),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
}

