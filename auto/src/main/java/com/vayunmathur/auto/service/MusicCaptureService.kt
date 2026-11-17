package com.vayunmathur.auto.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import com.vayunmathur.library.log.Log
import com.vayunmathur.auto.R
import com.vayunmathur.auto.platform.AutoSessionState
import com.vayunmathur.auto.platform.MusicCapture
import com.vayunmathur.auto.platform.MusicCaptureSinkHolder
import com.vayunmathur.auto.ui.MusicCaptureGrant

/**
 * Holds the media-projection token while music capture runs.
 *
 * Split from [ProjectionService] on purpose: on API 34+ starting a foreground
 * service with type `mediaProjection` requires the projection to already be
 * registered, so widening the projection service's type crashes bring-up
 * before any grant exists. This service starts only after the user grants the
 * consent dialog (see [MusicCaptureGrant]), feeds the ch5 sink through
 * [MusicCaptureSinkHolder] (set per session by the projection service, like
 * the `systemSink` seam `CarTts` uses), and stops when asked or when the grant
 * is gone. Fail-closed: without a stored grant it logs and stops immediately.
 */
class MusicCaptureService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val projection = MusicCaptureGrant.projection(this)
        if (projection == null) {
            Log.status(TAG, "no projection grant; not capturing")
            stopSelf()
            return START_NOT_STICKY
        }
        val notification = notification()
        startForeground(
            NOTIFICATION_ID,
            notification,
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION,
        )
        capture = MusicCapture(
            this,
            mediaSink = { MusicCaptureSinkHolder.sink },
            isPlayingNow = { AutoSessionState.nowPlaying.value?.playing == true },
            onEvent = AutoSessionState::onAudioEvent,
        ).also {
            it.startThread()
            it.start(projection)
        }
        return START_STICKY
    }

    override fun onDestroy() {
        capture?.releaseThread()
        capture = null
        super.onDestroy()
    }

    private var capture: MusicCapture? = null

    private fun notification(): Notification {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                getString(R.string.projection_channel),
                NotificationManager.IMPORTANCE_LOW,
            ),
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.music_capture_notification))
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setOngoing(true)
            .build()
    }

    companion object {
        private const val TAG = "MaAuto.MusicCaptureSvc"
        private const val CHANNEL_ID = "music-capture"
        private const val NOTIFICATION_ID = 2

        /**
         * Starts capture if a projection grant is stored; no-op otherwise.
         * Called when the session comes up and when the grant is first stored.
         */
        fun startIfGranted(context: Context) {
            if (MusicCaptureGrant.projection(context) == null) return
            context.startForegroundService(Intent(context, MusicCaptureService::class.java))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, MusicCaptureService::class.java))
        }
    }
}
