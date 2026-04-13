package com.vayunmathur.music.ui.dialogs
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ListItem
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.vayunmathur.library.util.DatabaseViewModel
import com.vayunmathur.music.R
import com.vayunmathur.library.util.NavBackStack
import com.vayunmathur.music.Route
import com.vayunmathur.music.data.Music
import com.vayunmathur.music.data.Playlist
import kotlinx.coroutines.launch

@Composable
fun AddToPlaylistDialog(backStack: NavBackStack<Route>, viewModel: DatabaseViewModel, musicId: Long) {
    val playlists by viewModel.data<Playlist>().collectAsState(initial = emptyList())
    var selectedPlaylistId by remember { mutableStateOf<Long?>(null) }
    val scope = rememberCoroutineScope()

    AlertDialog(
        onDismissRequest = { backStack.pop() },
        title = { Text(stringResource(R.string.dialog_add_to_playlist)) },
        text = {
            Column {
                playlists.forEach { playlist ->
                    ListItem(
                        headlineContent = { Text(playlist.name) },
                        leadingContent = {
                            RadioButton(
                                selected = selectedPlaylistId == playlist.id,
                                onClick = { selectedPlaylistId = playlist.id }
                            )
                        },
                        modifier = Modifier.clickable { selectedPlaylistId = playlist.id }
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = selectedPlaylistId != null,
                onClick = {
                    scope.launch {
                        viewModel.match<Playlist, Music>(selectedPlaylistId!!, musicId)
                        backStack.pop()
                    }
                }
            ) {
                Text(stringResource(R.string.dialog_ok))
            }
        },
        dismissButton = {
            TextButton(onClick = { backStack.pop() }) {
                Text(stringResource(R.string.dialog_cancel))
            }
        }
    )
}
