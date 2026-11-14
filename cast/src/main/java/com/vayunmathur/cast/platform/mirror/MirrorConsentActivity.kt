package com.vayunmathur.cast.platform.mirror

import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import com.vayunmathur.library.log.Log
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.getSystemService
import com.vayunmathur.cast.R
import com.vayunmathur.cast.service.CastService
import com.vayunmathur.library.util.AppMessages

private const val TAG = "MirrorConsent"

/**
 * Transparent trampoline that asks for screen-capture consent and hands the result to
 * [CastService].
 *
 * It exists because of a hard platform ordering constraint, not for style. On Android 14+:
 *
 *  1. `createScreenCaptureIntent()` needs an Activity to launch it and receive the result, so a
 *     Quick Settings tile can never obtain consent by itself.
 *  2. A `mediaProjection`-typed foreground service must already be in the foreground *before*
 *     `getMediaProjection()` is called, which is why the token is forwarded to the service rather
 *     than turned into a projection here.
 *  3. The consent token is **single-use**. It cannot be cached, so this runs afresh for every
 *     session - which is also why there is no boot receiver anywhere in this feature.
 *
 * **Not `android:noHistory`**, unlike a first guess would suggest for a trampoline. `noHistory`
 * finishes an activity as soon as it stops being visible, and launching the system consent dialog
 * does exactly that - the result callback would never arrive.
 */
class MirrorConsentActivity : ComponentActivity() {

    private val consent = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        val data = result.data
        if (result.resultCode != RESULT_OK || data == null) {
            // Declining is a choice, not an error, so it is not worth a message.
            Log.status(TAG, "screen capture consent declined")
            finish()
            return@registerForActivityResult
        }
        CastService.startMirroring(this, result.resultCode, data)
        finish()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val manager = getSystemService<MediaProjectionManager>()
        if (manager == null) {
            Log.status(TAG, "no MediaProjectionManager on this device")
            AppMessages.show(getString(R.string.cast_mirror_unavailable))
            finish()
            return
        }
        // Only on a fresh launch: registerForActivityResult restores a pending request across a
        // configuration change on its own, and asking again would stack a second consent dialog.
        if (savedInstanceState == null) {
            consent.launch(manager.createScreenCaptureIntent())
        }
    }

        /** Factory for the intent that starts the screen-capture consent flow. */
    companion object {
        /**
         * An intent that starts the consent flow.
         *
         * `NEW_TASK` because the callers are a tile and a notification, neither of which has a task
         * of its own. Deliberately **not** `CLEAR_TASK`: this activity has no `taskAffinity` of its
         * own, so it lands in the app's task and clearing it would finish `MainActivity` - which is
         * exactly where the user is standing when they press the in-app button.
         */
        fun intent(context: Context): Intent =
            Intent(context, MirrorConsentActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
}
