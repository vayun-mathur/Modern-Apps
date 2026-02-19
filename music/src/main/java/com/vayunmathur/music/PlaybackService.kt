package com.vayunmathur.music

import android.app.PendingIntent
import android.content.Intent
import android.os.Bundle
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.CommandButton
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture

class PlaybackService : MediaSessionService() {

    private var mediaSession: MediaSession? = null

    // Custom Command Constants
    companion object {
        const val ACTION_SHUFFLE = "ACTION_SHUFFLE"
        const val ACTION_REPEAT = "ACTION_REPEAT"
    }

    @OptIn(UnstableApi::class)
    override fun onCreate() {
        super.onCreate()

        val audioAttributes = AudioAttributes.Builder()
            .setUsage(C.USAGE_MEDIA)
            .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
            .build()

        val player = ExoPlayer.Builder(this)
            .setAudioAttributes(audioAttributes, true)
            .setHandleAudioBecomingNoisy(true)
            .build()

        // Create the session with our custom callback
        val intent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        mediaSession = MediaSession.Builder(this, player)
            .setSessionActivity(pendingIntent)
            .setCallback(MediaSessionCallback())
            .build()

        // Initialize the notification buttons for the first time
        updateNotificationButtons()
    }

    /**
     * This function builds the buttons and pushes them to the MediaSession.
     * The notification provider reads these 'preferences' to decide what to show.
     */
    @OptIn(UnstableApi::class)
    private fun updateNotificationButtons() {
        val session = mediaSession ?: return
        val player = session.player

        // 1. Shuffle Button
        val shuffleIcon = if (player.shuffleModeEnabled)
            CommandButton.ICON_SHUFFLE_ON else CommandButton.ICON_SHUFFLE_OFF

        val shuffleBtn = CommandButton.Builder(shuffleIcon)
            .setDisplayName("Shuffle")
            .setSessionCommand(SessionCommand(ACTION_SHUFFLE, Bundle.EMPTY))
            .build()

        // 2. Repeat Button
        val repeatIcon = when (player.repeatMode) {
            Player.REPEAT_MODE_ONE -> CommandButton.ICON_REPEAT_ONE
            Player.REPEAT_MODE_ALL -> CommandButton.ICON_REPEAT_ALL
            else -> CommandButton.ICON_REPEAT_OFF
        }

        val repeatBtn = CommandButton.Builder(repeatIcon)
            .setDisplayName("Repeat")
            .setSessionCommand(SessionCommand(ACTION_REPEAT, Bundle.EMPTY))
            .build()

        // Set the buttons. This list defines the order in the notification.
        session.setMediaButtonPreferences(ImmutableList.of(shuffleBtn, repeatBtn))
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? {
        return mediaSession
    }

    @OptIn(UnstableApi::class)
    private inner class MediaSessionCallback : MediaSession.Callback {
        // Handle the button clicks
        override fun onCustomCommand(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
            customCommand: SessionCommand,
            args: Bundle
        ): ListenableFuture<SessionResult> {

            when (customCommand.customAction) {
                ACTION_SHUFFLE -> {
                    session.player.shuffleModeEnabled = !session.player.shuffleModeEnabled
                    updateNotificationButtons() // Redraw notification with new icon
                }
                ACTION_REPEAT -> {
                    val nextMode = when (session.player.repeatMode) {
                        Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
                        Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
                        else -> Player.REPEAT_MODE_OFF
                    }
                    session.player.repeatMode = nextMode
                    updateNotificationButtons() // Redraw notification with new icon
                }
            }
            return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
        }
    }

    override fun onDestroy() {
        mediaSession?.run {
            player.release()
            release()
            mediaSession = null
        }
        super.onDestroy()
    }
}