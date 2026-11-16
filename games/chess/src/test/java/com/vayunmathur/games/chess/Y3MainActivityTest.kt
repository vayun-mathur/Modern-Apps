package com.vayunmathur.games.chess

import android.app.Application
import android.content.Intent
import android.net.Uri
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.Shadows
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

@RunWith(AndroidJUnit4::class)
class Y3MainActivityTest {
    private val app: Application get() = ApplicationProvider.getApplicationContext()

    private fun launch(intent: Intent = Intent(app, MainActivity::class.java)) =
        Robolectric.buildActivity(MainActivity::class.java, intent).also {
            it.create().start().resume()
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(2000)
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(500)
            Shadows.shadowOf(Looper.getMainLooper()).idle()
        }

    @Test fun plainLaunchReachesChessNavHost() {
        val c = launch()
        assertNotNull(c.get()); assertEquals(app.packageName, c.get().componentName?.packageName)
        c.pause().stop().destroy()
    }

    @Test fun oauthRedirectLaunchIsForwardedNotCrash() {
        val intent = Intent(app, MainActivity::class.java).apply {
            action = Intent.ACTION_VIEW
            data = Uri.parse("com.vayunmathur.games.chess://oauth?code=abc&state=xyz")
        }
        val c = launch(intent); assertNotNull(c.get())
        val second = Intent(app, MainActivity::class.java).apply {
            action = Intent.ACTION_VIEW; data = Uri.parse("https://example.com/not-oauth")
        }
        c.newIntent(second); Shadows.shadowOf(Looper.getMainLooper()).idle()
        assertNotNull(c.get())
        c.pause().stop().destroy()
    }

    @Test fun warmStartWithNoDataIntent() {
        val c = launch()
        c.newIntent(Intent(app, MainActivity::class.java))
        Shadows.shadowOf(Looper.getMainLooper()).idle()
        assertNotNull(c.get()); c.pause().stop().destroy()
    }
}
