package com.vayunmathur.youpipe.ui.dialogs

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vayunmathur.library.ui.Button
import com.vayunmathur.library.ui.Card
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.OutlinedTextField
import com.vayunmathur.library.ui.Text
import com.vayunmathur.library.util.NavBackStack
import com.vayunmathur.youpipe.R
import com.vayunmathur.library.ui.R as UiR
import com.vayunmathur.youpipe.Route
import com.vayunmathur.youpipe.platform.YouPipeViewModel

/** Dialog to create a new playlist. Disabled while blank or a duplicate name. */
@Composable
fun CreatePlaylist(backStack: NavBackStack<Route>, youPipeViewModel: YouPipeViewModel) {
    val playlists by youPipeViewModel.playlists.collectAsStateWithLifecycle()
    val existingNames = playlists.map { it.name }
    var name by remember { mutableStateOf("") }

    Dialog({ backStack.pop() }) {
        Card {
            Column(Modifier.padding(16.dp)) {
                Text(
                    stringResource(R.string.title_create_playlist),
                    style = MaterialTheme.typography.titleLarge,
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    name,
                    { name = it },
                    label = { Text(stringResource(R.string.label_playlist_name)) },
                )
                Button(
                    {
                        youPipeViewModel.playlistOps.createPlaylist(name.trim())
                        backStack.pop()
                    },
                    Modifier.fillMaxWidth().padding(top = 8.dp),
                    enabled = name.isNotBlank() && name.trim() !in existingNames,
                ) {
                    Text(stringResource(UiR.string.create))
                }
            }
        }
    }
}
