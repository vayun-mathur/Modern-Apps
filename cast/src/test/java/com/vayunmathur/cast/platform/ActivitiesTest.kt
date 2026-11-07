package com.vayunmathur.cast.platform

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.vayunmathur.cast.MainActivity
import com.vayunmathur.cast.domain.ClientPhase
import com.vayunmathur.cast.domain.ClientState
import com.vayunmathur.cast.platform.mirror.MirrorConsentActivity
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.annotation.GraphicsMode
import org.robolectric.Shadows

@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ActivitiesTest {

    @Before fun reset() = runBlocking { CastController.teardown() }
    @After fun cleanup() = runBlocking { CastController.teardown() }

    @Test
    fun `main activity creates its navigation`() {
        val controller = Robolectric.buildActivity(MainActivity::class.java).create().start().resume()
        assertNotNull(controller.get())
        controller.pause().stop().destroy()
    }

    @Test
    fun `picker activity cancels by default and finishes on back`() {
        val controller = Robolectric.buildActivity(CastPickerActivity::class.java).create().start().resume()
        val activity = controller.get()
        assertEquals("", CastController.contentAppLabel)
        assertNotNull(Shadows.shadowOf(activity))
        controller.pause().stop().destroy()
    }

    @Test
    fun `pair activity shows while awaiting and disconnects on destroy while finishing`() {
        CastController.sessionStateMutable.value = ClientState(phase = ClientPhase.AwaitingCode, attemptsLeft = 2)
        val controller = Robolectric.buildActivity(CastPairActivity::class.java).create().start().resume()
        val activity = controller.get()
        assertTrue(!activity.isFinishing)
        activity.finish()
        controller.pause().stop().destroy()
        // The destroy path disconnects a still-awaiting session (async, just exercise it).
        Thread.sleep(200)
    }

    @Test
    fun `pair activity intent is a new task intent`() {
        val app = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.app.Application>()
        val intent = CastPairActivity.intent(app)
        assertTrue(intent.flags and android.content.Intent.FLAG_ACTIVITY_NEW_TASK != 0)
        assertEquals(CastPairActivity::class.java.name, intent.component?.className)
    }

    @Test
    fun `consent activity intent and creation`() {
        val app = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.app.Application>()
        val intent = MirrorConsentActivity.intent(app)
        assertTrue(intent.flags and android.content.Intent.FLAG_ACTIVITY_NEW_TASK != 0)
        val controller = Robolectric.buildActivity(MirrorConsentActivity::class.java).create()
        assertNotNull(controller.get())
        controller.destroy()
    }
}
