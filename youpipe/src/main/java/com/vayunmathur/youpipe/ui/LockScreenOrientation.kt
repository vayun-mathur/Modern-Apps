package com.vayunmathur.youpipe.ui
import android.app.Activity
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import kotlinx.coroutines.flow.first
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.vayunmathur.library.ui.Button
import com.vayunmathur.library.ui.Card
import com.vayunmathur.library.ui.ExternalIntents
import com.vayunmathur.library.ui.Text
import com.vayunmathur.library.util.NavBackStack
import com.vayunmathur.youpipe.R
import com.vayunmathur.youpipe.Route
import com.vayunmathur.youpipe.ui.findActivity
import com.vayunmathur.youpipe.platform.CastPlayback
import com.vayunmathur.youpipe.ui.rememberIsInPipMode
import com.vayunmathur.youpipe.platform.VideoDetailActions
import com.vayunmathur.youpipe.platform.VideoDetailUiState
import com.vayunmathur.youpipe.platform.YouPipeViewModel
import com.vayunmathur.youpipe.platform.applyDownloadedStreams
import com.vayunmathur.youpipe.platform.clearVideoError
import com.vayunmathur.youpipe.platform.loadVideo
import com.vayunmathur.youpipe.network.videoIDtoURL
import com.vayunmathur.youpipe.platform.DownloadManager
import kotlin.time.Instant


@Composable
fun LockScreenOrientation(orientation: Int) {
    val context = LocalContext.current
    DisposableEffect(orientation) {
        val activity = context.findActivity()
        val originalOrientation = activity.requestedOrientation
        activity.requestedOrientation = orientation

        onDispose {
            // Restore original orientation when leaving the screen
            activity.requestedOrientation = originalOrientation
        }
    }
}
