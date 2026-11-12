package com.vayunmathur.communicate.notifications

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.os.Build
import com.vayunmathur.library.log.Log

private const val TAG = "CallFullScreenIntent"

@Volatile
private var reportedFullScreenDenied = false

/**
 * Attach the incoming-call full-screen intent when the app may use it.
 *
 * Android 14 made `USE_FULL_SCREEN_INTENT` a special app access the user (or the store) can revoke, and
 * a revoked app's full-screen intent is silently dropped. When
 * [NotificationManager.canUseFullScreenIntent] says no, this leaves the intent off and the call shows as
 * the `CallStyle` heads-up notification instead — still with answer / decline, still ringing via
 * `CallRinger` — rather than a notification that claims a full-screen treatment it will not get.
 */
fun Notification.Builder.setCallFullScreenIntent(context: Context, intent: PendingIntent): Notification.Builder {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
        val nm = context.getSystemService(NotificationManager::class.java)
        if (nm != null && !nm.canUseFullScreenIntent()) {
            if (!reportedFullScreenDenied) {
                reportedFullScreenDenied = true
                Log.status(TAG, "full-screen intent access revoked; incoming calls post as heads-up only")
            }
            return this
        }
    }
    return setFullScreenIntent(intent, true)
}
