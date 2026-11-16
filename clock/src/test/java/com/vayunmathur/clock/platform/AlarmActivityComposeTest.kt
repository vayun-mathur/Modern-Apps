package com.vayunmathur.clock.platform

import android.content.Context
import android.content.Intent
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.vayunmathur.clock.FakeAndroidKeyStore
import org.junit.Before
import org.junit.Rule
import org.junit.runner.RunWith
import kotlin.test.Test
import kotlin.test.assertTrue

/** The ringing screen as the user sees it: composed by AlarmActivity itself. */
@RunWith(AndroidJUnit4::class)
class AlarmActivityComposeTest {
    @Before fun setUp() = FakeAndroidKeyStore.install()

    private val ctx: Context get() = ApplicationProvider.getApplicationContext()

    @get:Rule val rule = createComposeRule()

    @Test fun ringingScreenShowsPlaceholderAndStopFinishes() {
        val intent = Intent(ctx, AlarmActivity::class.java).apply { putExtra("ALARM_ID", 4242L) }
        val scenario = ActivityScenario.launch<AlarmActivity>(intent)
        // The alarm row cannot be read on the host JVM (SQLCipher), so the screen
        // composes with its placeholder time and a disabled snooze; Stop still works:
        // dismissing with no loaded alarm stops the sound service and finishes.
        rule.onNodeWithText("--:--").assertExists()
        rule.onNodeWithText(ctx.getString(com.vayunmathur.clock.R.string.button_stop)).performClick()
        var finishing = false
        scenario.onActivity { finishing = it.isFinishing }
        assertTrue(finishing)
        scenario.close()
    }
}
