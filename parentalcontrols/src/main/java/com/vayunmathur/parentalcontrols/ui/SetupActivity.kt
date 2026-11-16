package com.vayunmathur.parentalcontrols.ui

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.os.Bundle
import com.vayunmathur.library.log.Log
import com.vayunmathur.parentalcontrols.MainActivity
import com.vayunmathur.parentalcontrols.auth.ParentPin

private const val TAG = "ParentalControlsSetup"

/**
 * Where `android.app.supervision.action.INSTALL_SUPERVISION_APP` lands.
 *
 * **It draws nothing.** Settings looks for an activity answering that action so it can show the
 * Parental controls entry on a device where the supervision app still needs installing, and it is
 * the second half of `TopLevelSupervisionPreferenceController`'s availability test - without it,
 * the row is hidden until supervision is already on, which is a chicken and egg. On MAOS the app
 * is preinstalled, so there is nothing to install and nothing to explain: the honest response is
 * to hand straight to the platform's own setup flow.
 *
 * A screen here would be a dead end. Every control - the PIN, schedules, app limits, content
 * filters - lives in the navigation host, so anything drawn in this activity could only tell
 * the user to go back to where they already were. When no PIN exists yet, the host is
 * started at its PIN-setup destination first (behind the platform flow started below).
 */
class SetupActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Imposed enforcement always has a gatekeeper: before the platform setup flow runs,
        // make sure a parent PIN exists. First run lands on PIN creation; afterwards the
        // platform flow proceeds as before.
        if (!ParentPin.get(this).isSet()) {
            startActivity(MainActivity.pinSetupIntent(this))
        }
        val enable = Intent(ACTION_ENABLE_SUPERVISION).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        try {
            startActivity(enable)
        } catch (e: ActivityNotFoundException) {
            // Only reachable on a build whose Settings has no supervision flow at all, in which
            // case this app should not have been resolvable either. Logged rather than shown:
            // there is no useful action for the user, and a dialog from an app with no icon and
            // no other UI would be more confusing than the entry simply not working.
            Log.status(TAG, "no activity for $ACTION_ENABLE_SUPERVISION; is this a supervision build?", e)
        }
        finish()
    }

    private companion object {
        const val ACTION_ENABLE_SUPERVISION = "android.app.supervision.action.ENABLE_SUPERVISION"
    }
}
