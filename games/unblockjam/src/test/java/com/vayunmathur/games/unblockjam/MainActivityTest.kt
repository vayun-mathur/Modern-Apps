package com.vayunmathur.games.unblockjam

import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.games.unblockjam.data.LevelPack
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
class MainActivityTest {
    @Test
    fun activityLaunchesAndInitializesPacks() {
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        val activity = controller.get()
        Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
        assertFalse(activity.isFinishing)
        assertEquals(7, LevelPack.PACKS.size)
        assertTrue(LevelPack.PACKS.all { it.levels.isNotEmpty() })
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        assertEquals("Unblock Jam", ctx.getString(R.string.app_name))
        controller.pause().stop().destroy()
    }
}
