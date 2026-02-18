package com.vayunmathur.music.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.media3.common.MediaItem
import androidx.navigation3.runtime.NavBackStack
import com.vayunmathur.library.util.DatabaseViewModel
import com.vayunmathur.library.util.findActivity
import com.vayunmathur.music.MainActivity
import com.vayunmathur.music.PlaybackManager
import com.vayunmathur.music.Route
import com.vayunmathur.music.database.Music

@Composable
fun SongScreen(backStack: NavBackStack<Route>, viewModel: DatabaseViewModel, songID: Long) {
    val song by viewModel.get<Music>(songID) {Music(0, "Unknown", "Unknown", "Unknown", "Unknown")}
    val context = LocalContext.current
    val playbackManager = remember { PlaybackManager.getInstance(context) }
    LaunchedEffect(song) {
        if(song.id != 0L) {
            playbackManager.playSong(song)
            println(song)
        }
    }
}