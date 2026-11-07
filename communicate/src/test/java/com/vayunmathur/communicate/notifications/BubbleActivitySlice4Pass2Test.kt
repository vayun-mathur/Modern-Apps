package com.vayunmathur.communicate.notifications

import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import kotlin.test.Test
import kotlin.test.assertNotNull
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Slice-4 pass-2: bubble activity intent parsing (line fallback, extras) under Robolectric. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BubbleActivitySlice4Pass2Test {

    private val ctx get() = ApplicationProvider.getApplicationContext<android.content.Context>()

    @Test
    fun activityCreatesWithSimExtras() {
        val intent = Intent(ctx, BubbleActivity::class.java).apply {
            putExtra(ConversationSpace.EXTRA_LINE, "Sim")
            putExtra(ConversationSpace.EXTRA_ADDRESS, "+1555")
            putExtra(ConversationSpace.EXTRA_THREAD_ID, 7L)
            putExtra(ConversationSpace.EXTRA_TITLE, "Pat")
            putExtra(ConversationSpace.EXTRA_SUB_ID, 2)
            putExtra(ConversationSpace.EXTRA_IS_GROUP, false)
        }
        val controller = Robolectric.buildActivity(BubbleActivity::class.java, intent).setup()
        assertNotNull(controller.get())
        controller.pause().stop().destroy()
    }

    @Test
    fun activityCreatesWithBogusLineFallingBackToSim() {
        val intent = Intent(ctx, BubbleActivity::class.java).apply {
            putExtra(ConversationSpace.EXTRA_LINE, "NotALine")
            putExtra(ConversationSpace.EXTRA_ADDRESS, "+1999")
        }
        val controller = Robolectric.buildActivity(BubbleActivity::class.java, intent).setup()
        assertNotNull(controller.get())
        controller.pause().stop().destroy()
    }

    @Test
    fun activityCreatesWithNoExtras() {
        val controller = Robolectric.buildActivity(BubbleActivity::class.java, Intent(ctx, BubbleActivity::class.java)).setup()
        assertNotNull(controller.get())
        controller.pause().stop().destroy()
    }
}
