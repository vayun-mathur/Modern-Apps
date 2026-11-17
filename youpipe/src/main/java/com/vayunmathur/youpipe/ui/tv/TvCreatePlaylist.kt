package com.vayunmathur.youpipe.ui.tv

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vayunmathur.library.tv.TvButton
import com.vayunmathur.library.tv.TvInitialFocus
import com.vayunmathur.library.tv.TvScreenHeader
import com.vayunmathur.library.tv.TvTextField
import com.vayunmathur.library.ui.R as UiR
import com.vayunmathur.library.util.NavBackStack
import com.vayunmathur.youpipe.R
import com.vayunmathur.youpipe.platform.YouPipeViewModel

/**
 * TV create-playlist: a [TvTextField] (IME only on CENTER) + focused create row.
 *
 * Mirrors the phone `CreatePlaylist` validation: non-blank and no duplicate name.
 */
@Composable
fun TvCreatePlaylistPage(
    backStack: NavBackStack<TvRoute>,
    ypvm: YouPipeViewModel,
) {
    val allPlaylists by ypvm.playlists.collectAsStateWithLifecycle()
    var name by remember { mutableStateOf("") }
    val createFocus = remember { FocusRequester() }

    val canCreate = name.isNotBlank() && allPlaylists.none { it.name == name.trim() }

    TvInitialFocus(createFocus)
    TvSettingsBody {
        Column(Modifier.fillMaxSize()) {
            TvScreenHeader(title = stringResource(R.string.title_create_playlist))
            Column(
                Modifier.padding(start = 48.dp, end = 48.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
            TvTextField(
                value = name,
                onValueChange = { name = it },
                placeholder = stringResource(R.string.label_playlist_name),
                onSubmit = {
                    if (canCreate) {
                        ypvm.playlistOps.createPlaylist(name.trim())
                        backStack.pop()
                    }
                },
                focusRequester = createFocus,
                modifier = Modifier.fillMaxWidth(),
            )
            androidx.compose.foundation.layout.Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                TvButton(
                    text = stringResource(R.string.new_playlist),
                    onClick = {
                        ypvm.playlistOps.createPlaylist(name.trim())
                        backStack.pop()
                    },
                    enabled = canCreate,
                    primary = true,
                )
                TvButton(
                    text = stringResource(UiR.string.cancel),
                    onClick = { backStack.pop() },
                )
            }
            }
        }
    }
}
