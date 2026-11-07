package com.vayunmathur.email.ui

import android.content.Intent
import androidx.core.net.toUri
import kotlin.test.Test
import kotlin.test.assertTrue
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config

/**
 * [OAuthActivity]: a redirect with no data finishes immediately; an error
 * redirect completes (failure, no PKCE session) and hands back to MainActivity.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class OAuthActivityTest {

    @Test
    fun `no redirect data finishes the activity`() {
        val controller = Robolectric.buildActivity(OAuthActivity::class.java, Intent())
        val activity = controller.create().get()
        assertTrue(activity.isFinishing)
        controller.destroy()
    }

    @Test
    fun `error redirect finishes after handling`() {
        val intent = Intent(Intent.ACTION_VIEW, "com.vayunmathur.email://oauth?error=access_denied".toUri())
        val controller = Robolectric.buildActivity(OAuthActivity::class.java, intent)
        val activity = controller.create().get()
        // complete() runs in lifecycleScope; wait for the activity to finish.
        repeat(200) {
            if (activity.isFinishing) return@repeat
            Thread.sleep(25)
            Shadows.shadowOf(activity.mainLooper).idle()
        }
        assertTrue(activity.isFinishing)
        controller.destroy()
    }
}
