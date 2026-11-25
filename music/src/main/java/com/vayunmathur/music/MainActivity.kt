package com.vayunmathur.music

import android.Manifest
import android.os.Build
import androidx.activity.viewModels
import androidx.compose.runtime.Composable
import com.vayunmathur.library.ui.AppPermissionsGate
import com.vayunmathur.library.ui.AppPermissionsSpec
import com.vayunmathur.library.ui.MaActivity
import com.vayunmathur.library.ui.PermissionRequirement
import com.vayunmathur.music.R
import com.vayunmathur.music.data.MusicRepository
import com.vayunmathur.music.platform.MusicViewModel
import com.vayunmathur.music.platform.MusicViewModelFactory
import com.vayunmathur.music.platform.PlaybackManager
import com.vayunmathur.music.sync.WearMusicSync

/** Main Activity: a ma activity implementation for main activity. */
class MainActivity : MaActivity() {
    private val musicViewModel: MusicViewModel by viewModels {
        MusicViewModelFactory(application, MusicRepository.get(application), PlaybackManager.getInstance(this))
    }

    override fun onCreateBeforeContent() {
        // Phone side of the Wear OS companion: page server + playback-state
        // streamer + op executor (see sync/WearMusicSync.kt).
        WearMusicSync.start(this)
    }

    @Composable
    override fun Content() {
        val (permissions, message) = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
            arrayOf(Manifest.permission.READ_MEDIA_AUDIO) to getString(R.string.grant_audio_permissions)
        else
            arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE) to getString(R.string.grant_storage_permissions)
        AppPermissionsGate(
            spec = AppPermissionsSpec(
                title = message,
                requirements = listOf(PermissionRequirement.Runtime(permissions))
            )
        ) {
            Navigation(musicViewModel)
        }
    }
}
