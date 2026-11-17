package com.vayunmathur.youpipe

import android.content.Intent
import android.content.res.Configuration
import androidx.activity.viewModels
import androidx.compose.runtime.Composable
import com.vayunmathur.library.ui.MaActivity
import com.vayunmathur.library.util.DataStoreUtils
import com.vayunmathur.library.util.OfflineAware
import com.vayunmathur.youpipe.data.SubscriptionRepository
import com.vayunmathur.youpipe.network.parseSharedVideoId
import com.vayunmathur.youpipe.platform.DEFAULT_PAGE_KEY
import com.vayunmathur.youpipe.platform.YouPipeViewModel
import com.vayunmathur.youpipe.platform.YouPipeViewModelFactory
import com.vayunmathur.youpipe.platform.defaultBackStack

/** Main Activity: a ma activity implementation for main activity. */
class MainActivity : MaActivity() {
    private val youPipeViewModel: YouPipeViewModel by viewModels {
        YouPipeViewModelFactory(
            application,
            SubscriptionRepository.get(application),
        )
    }
    private lateinit var initialBackStack: List<Route>

    override fun onCreateBeforeContent() {
        // Touch the VM so its init { setupHourlyTask(...) } runs at startup
        // (replaces the previous LaunchedEffect(Unit) in setContent).
        youPipeViewModel
        initialBackStack = resolveInitialBackStack(intent)
    }

    @Composable
    override fun Content() {
        OfflineAware {
            Navigation(initialBackStack, youPipeViewModel)
        }
    }

    /**
     * Decide the initial backstack at launch. A shared YouTube URL (ACTION_SEND) routes to the
     * video, optionally seeding an add-to-Watch-later or add-to-playlist dialog depending on which
     * share target was tapped. Otherwise a `watch` deep-link wins; failing that, fall back to the
     * user's chosen default page (see [DEFAULT_PAGE_KEY]), defaulting to Home. Read synchronously
     * here because this runs in [onCreate] before `setContent`.
     */
    private fun resolveInitialBackStack(intent: Intent): List<Route> {
        if (intent.action == Intent.ACTION_APPLICATION_PREFERENCES) {
            return listOf(Route.Settings)
        }
        if (intent.action == Intent.ACTION_SEND && intent.type == "text/plain") {
            val videoID = intent.getStringExtra(Intent.EXTRA_TEXT)?.let { parseSharedVideoId(it) }
            if (videoID != null) {
                return when (intent.component?.className) {
                    "$packageName.ShareWatchLater" ->
                        listOf(Route.Home, Route.VideoPage(videoID), Route.AddToWatchLater(videoID))
                    "$packageName.SharePlaylist" ->
                        listOf(
                            Route.Home,
                            Route.VideoPage(videoID),
                            Route.AddToPlaylist(videoID, includeWatchLater = false),
                        )
                    else -> listOf(Route.Home, Route.VideoPage(videoID))
                }
            }
        }
        val uri = intent.data
        // Total parse: watch, youtu.be, /shorts/, /live/ and /embed/ links route to the
        // video; anything without an 11-char id (channel pages, playlists, garbage `v`
        // params that used to crash videoURLtoID in onCreate) falls through to default.
        if (uri != null) {
            parseSharedVideoId(uri.toString())?.let { videoID ->
                return listOf(Route.Home, Route.VideoPage(videoID))
            }
        }
        return defaultBackStack(DataStoreUtils.getInstance(this).getString(DEFAULT_PAGE_KEY))
    }
    override fun onPictureInPictureModeChanged(
        isInPictureInPictureMode: Boolean,
        newConfig: Configuration
    ) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        // Previously we stopped PlaybackService when PiP closed (isFinishing). New behavior:
        // Keep audio playing and switch to audio-only when PiP is dismissed. PlaybackService
        // handles the audio-only switch via track selection override; we do NOT stop it here.
    }

    override fun onDestroy() {
        // Only stop service when truly destroying the whole activity, not when entering PiP.
        // When the user swipes away PiP window, system destroys activity with isFinishing=true,
        // but we want to KEEP audio (background playback). So we do NOT stop service here.
        // PlaybackService's own onTaskRemoved will handle final cleanup when notification is
        // dismissed or user explicitly stops.
        super.onDestroy()
    }
}
