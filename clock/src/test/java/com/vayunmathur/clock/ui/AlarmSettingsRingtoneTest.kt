package com.vayunmathur.clock.ui

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.RingtoneManager
import android.net.Uri
import androidx.activity.ComponentActivity
import androidx.activity.result.ActivityResult
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.clock.Route
import com.vayunmathur.clock.platform.ClockViewModel
import com.vayunmathur.library.ui.ringtoneTitle
import com.vayunmathur.library.util.DataStoreUtils
import com.vayunmathur.library.util.NavBackStack
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/**
 * The alarm-settings ringtone row: tapping it launches the system picker, and
 * the picked URI is persisted as the default for new alarms. A cancelled pick
 * changes nothing.
 */
@RunWith(RobolectricTestRunner::class)
class AlarmSettingsRingtoneTest {

    companion object {
        /** Earlier classes' background DB errors surface here otherwise (see ClockDialogsDeepTest). */
        @org.junit.BeforeClass @JvmStatic fun drainStoredDbErrors() {
            repeat(8) {
                try {
                    kotlinx.coroutines.test.runTest { }
                } catch (e: IllegalStateException) {
                    if (e.javaClass.simpleName != "UncaughtExceptionsBeforeTest") throw e
                }
                Thread.sleep(400)
            }
        }
    }

    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    private val ctx: Context get() = ApplicationProvider.getApplicationContext()

    private fun awaitValue(deadlineMs: Long = 10_000, read: () -> Boolean): Boolean {
        val deadline = System.currentTimeMillis() + deadlineMs
        while (System.currentTimeMillis() < deadline) {
            if (read()) return true
            Thread.sleep(50)
            rule.waitForIdle()
        }
        return read()
    }

    @Test fun ringtonePickPersistsAndCancelKeeps() {
        val ds = DataStoreUtils.getInstance(ctx)
        val stack = NavBackStack(arrayOf<Route>(Route.Alarm, Route.AlarmSettings))
        rule.setContent { AlarmSettingsPage(stack, ds) }
        rule.waitForIdle()

        val title = ringtoneTitle(ctx, ds.getString(ClockViewModel.KEY_DEFAULT_RINGTONE), RingtoneManager.TYPE_ALARM)
        rule.onNodeWithText(title).performClick()
        rule.waitForIdle()
        val request = shadowOf(rule.activity).nextStartedActivityForResult
        assertNotNull(request, "ringtone row should launch the picker")

        val picked = Uri.parse("content://media/internal/audio/media/42")
        rule.activity.activityResultRegistry.dispatchResult(
            request.requestCode,
            ActivityResult(Activity.RESULT_OK, Intent().putExtra(RingtoneManager.EXTRA_RINGTONE_PICKED_URI, picked)),
        )
        rule.waitForIdle()
        assertEquals(
            true,
            awaitValue { ds.getString(ClockViewModel.KEY_DEFAULT_RINGTONE) == picked.toString() },
        )

        // Cancel the next pick: the stored default must not move.
        val titleAfter = ringtoneTitle(ctx, picked.toString(), RingtoneManager.TYPE_ALARM)
        rule.onNodeWithText(titleAfter).performClick()
        rule.waitForIdle()
        val request2 = shadowOf(rule.activity).nextStartedActivityForResult
        assertNotNull(request2)
        rule.activity.activityResultRegistry.dispatchResult(
            request2.requestCode,
            ActivityResult(Activity.RESULT_CANCELED, null),
        )
        rule.waitForIdle()
        assertEquals(picked.toString(), ds.getString(ClockViewModel.KEY_DEFAULT_RINGTONE))
    }
}
