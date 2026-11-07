package com.vayunmathur.astronomy

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import kotlin.test.assertFalse

/**
 * Robolectric tests for [MainActivity]: it creates, composes the
 * permission gate / navigation, and tears down cleanly on the JVM.
 * Location permission starts ungranted, so the gate is what composes;
 * the sky itself is covered by the screen tests.
 */
@RunWith(AndroidJUnit4::class)
class MainActivityRoboTest {

    @Test
    fun `activity creates and destroys cleanly`() {
        val controller = Robolectric.buildActivity(MainActivity::class.java)
        val activity = controller.setup().get()
        assertFalse(activity.isFinishing)
        controller.pause().stop().destroy()
    }

    @Test
    fun `activity survives a restart cycle`() {
        val controller = Robolectric.buildActivity(MainActivity::class.java)
        controller.setup()
        controller.restart()
        val activity = controller.get()
        assertFalse(activity.isFinishing)
        controller.pause().stop().destroy()
    }
}
